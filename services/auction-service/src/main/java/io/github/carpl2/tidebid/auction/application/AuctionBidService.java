package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionBidCommandRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionBidCommandTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionProxyBidRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.IdGenerator;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommand;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandIdempotency;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandPlanner;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandType;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
@Profile({"local-db", "nacos"})
public class AuctionBidService {

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,48}");
    private static final int MAXIMUM_CAS_ATTEMPTS = 3;

    private final AuctionSessionRepository sessionRepository;
    private final AuctionRegistrationRepository registrationRepository;
    private final AuctionProxyBidRepository proxyRepository;
    private final AuctionBidCommandRepository commandRepository;
    private final AuctionBidCommandTransaction commandTransaction;
    private final AuctionSessionLifecycleService lifecycleService;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public AuctionBidService(
            AuctionSessionRepository sessionRepository,
            AuctionRegistrationRepository registrationRepository,
            AuctionProxyBidRepository proxyRepository,
            AuctionBidCommandRepository commandRepository,
            AuctionBidCommandTransaction commandTransaction,
            AuctionSessionLifecycleService lifecycleService,
            IdGenerator idGenerator,
            Clock clock
    ) {
        this.sessionRepository = sessionRepository;
        this.registrationRepository = registrationRepository;
        this.proxyRepository = proxyRepository;
        this.commandRepository = commandRepository;
        this.commandTransaction = commandTransaction;
        this.lifecycleService = lifecycleService;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public Result place(PlaceBidCommand command) {
        PlaceBidCommand valid = requireCommand(command);
        BigDecimal amount = AuctionBidPolicy.normalizeAmount(valid.amount());
        AuctionBidCommandIdempotency.Request request = new AuctionBidCommandIdempotency.Request(
                valid.bidderId(), valid.requestId(), AuctionBidCommandType.MANUAL_BID,
                payloadHash(valid.auctionId(), amount));
        Result replay = inspectExisting(valid, request, amount);
        if (replay != null) return replay;

        AuctionBidCasRetryCoordinator coordinator = new AuctionBidCasRetryCoordinator(MAXIMUM_CAS_ATTEMPTS);
        try {
            AuctionBidCasRetryCoordinator.Outcome<Attempt, AuctionSession> outcome = coordinator.execute(
                    ignored -> planAndCommit(valid, request, amount),
                    () -> loadSession(valid.auctionId()));
            if (outcome.status() == AuctionBidCasRetryCoordinator.Status.EXHAUSTED) {
                throw new AuctionBidConflictException(outcome.latestSnapshot());
            }
            Attempt attempt = outcome.committed();
            AuctionSession latest = loadStoredSession(valid.auctionId());
            return result(amount, attempt.committed().command(), attempt.committed().bids(), latest,
                    latest.endAt().isAfter(attempt.before().endAt()), false);
        } catch (AuctionBidCommandTransaction.DuplicateCommandException duplicate) {
            Result concurrentReplay = inspectExisting(valid, request, amount);
            if (concurrentReplay == null) {
                throw new BusinessException(AuctionErrorCode.BID_CONFLICT,
                        "The same request is still being processed");
            }
            return concurrentReplay;
        }
    }

    private Attempt planAndCommit(
            PlaceBidCommand command,
            AuctionBidCommandIdempotency.Request request,
            BigDecimal amount
    ) {
        AuctionSession session = loadSession(command.auctionId());
        AuctionRegistration registration = registrationRepository
                .findByAuctionAndBidder(session.id(), command.bidderId()).orElse(null);
        Instant now = now();
        AuctionBidPolicy.validate(session, registration, command.bidderId(), amount, now);
        AuctionBidCommandPlanner.Plan plan = new AuctionBidCommandPlanner().plan(
                session, proxyRepository.findActiveByAuction(session.id()),
                AuctionBidCommandPlanner.Command.manualBid(command.bidderId(), amount));

        AuctionBidCommandIdempotency idempotency = new AuctionBidCommandIdempotency();
        AuctionBidCommand processing = idempotency.start(nextId(), session.id(), request, now);
        AuctionBidCommand completed = idempotency.complete(processing, plan, now);
        List<BidRecord> bids = plan.bids().stream().map(bid -> new BidRecord(
                nextId(), session.id(), bid.bidderId(), command.requestId(), bid.source(), processing.id(),
                bid.amount(), bid.previousPrice(), bid.sequenceNo(), now)).toList();
        AuctionBidCommandTransaction.CommittedCommand committed = commandTransaction.commit(
                new AuctionBidCommandTransaction.CommitRequest(
                        processing, completed, AuctionBidCommandTransaction.ProxyMutation.none(),
                        bids, session.version()));
        return new Attempt(session, committed);
    }

    private Result inspectExisting(
            PlaceBidCommand command,
            AuctionBidCommandIdempotency.Request request,
            BigDecimal amount
    ) {
        AuctionBidCommand existing = commandRepository
                .findByActorAndRequest(command.bidderId(), command.requestId()).orElse(null);
        AuctionBidCommandIdempotency.Decision decision = new AuctionBidCommandIdempotency().inspect(existing, request);
        return switch (decision.type()) {
            case NEW -> null;
            case PAYLOAD_CONFLICT -> throw new BusinessException(AuctionErrorCode.IDEMPOTENCY_CONFLICT);
            case IN_PROGRESS -> throw new BusinessException(AuctionErrorCode.BID_CONFLICT,
                    "The same request is still being processed");
            case REPLAY -> result(amount, decision.existing(),
                    sessionRepository.findBidsByCommandId(decision.existing().id()),
                    loadStoredSession(command.auctionId()), false, true);
        };
    }

    private static Result result(
            BigDecimal requestedAmount,
            AuctionBidCommand command,
            List<BidRecord> bids,
            AuctionSession session,
            boolean extended,
            boolean replayed
    ) {
        boolean leading = Boolean.TRUE.equals(command.resultLeading());
        boolean outbidByProxy = !leading && bids.stream()
                .anyMatch(bid -> bid.source() == io.github.carpl2.tidebid.auction.domain.BidSource.PROXY);
        return new Result(command, requestedAmount, bids, session, leading, outbidByProxy, extended, replayed);
    }

    private AuctionSession loadSession(long auctionId) {
        return sessionRepository.findSessionById(auctionId)
                .map(lifecycleService::advanceToCurrentState)
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.AUCTION_NOT_FOUND));
    }

    private AuctionSession loadStoredSession(long auctionId) {
        return sessionRepository.findSessionById(auctionId)
                .orElseThrow(() -> new IllegalStateException("Auction session disappeared after bid command"));
    }

    private static PlaceBidCommand requireCommand(PlaceBidCommand command) {
        if (command == null || command.bidderId() <= 0 || command.auctionId() <= 0) {
            throw new BusinessException(AuctionErrorCode.AUCTION_INVALID,
                    "bidderId and auctionId must be positive");
        }
        String requestId = command.requestId() == null ? "" : command.requestId().trim();
        if (!SAFE_REQUEST_ID.matcher(requestId).matches()) {
            throw new BusinessException(AuctionErrorCode.AUCTION_INVALID,
                    "requestId must contain 8 to 48 letters, digits, underscores, or hyphens");
        }
        return new PlaceBidCommand(command.bidderId(), command.auctionId(), requestId, command.amount());
    }

    private static String payloadHash(long auctionId, BigDecimal amount) {
        String payload = auctionId + ":" + amount.toPlainString();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long nextId() {
        long id = idGenerator.nextId();
        if (id <= 0) throw new IllegalStateException("idGenerator returned a non-positive id");
        return id;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private record Attempt(AuctionSession before, AuctionBidCommandTransaction.CommittedCommand committed) { }

    public record Result(
            AuctionBidCommand command,
            BigDecimal requestedAmount,
            List<BidRecord> bids,
            AuctionSession session,
            boolean leading,
            boolean outbidByProxy,
            boolean extended,
            boolean replayed
    ) {
        public Result {
            command = Objects.requireNonNull(command, "command must not be null");
            requestedAmount = Objects.requireNonNull(requestedAmount, "requestedAmount must not be null");
            bids = List.copyOf(Objects.requireNonNull(bids, "bids must not be null"));
            session = Objects.requireNonNull(session, "session must not be null");
        }
    }

    public record PlaceBidCommand(long bidderId, long auctionId, String requestId, BigDecimal amount) { }
}

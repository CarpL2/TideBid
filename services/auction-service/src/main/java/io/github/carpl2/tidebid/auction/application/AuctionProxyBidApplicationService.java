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
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandType;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBid;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBidLifecycle;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBidStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
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
import java.util.Optional;

@Service
@Profile({"local-db", "nacos"})
public class AuctionProxyBidApplicationService {

    private final AuctionSessionRepository sessionRepository;
    private final AuctionRegistrationRepository registrationRepository;
    private final AuctionProxyBidRepository proxyRepository;
    private final AuctionBidCommandRepository commandRepository;
    private final AuctionBidCommandTransaction commandTransaction;
    private final AuctionSessionLifecycleService lifecycleService;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public AuctionProxyBidApplicationService(
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

    public Optional<AuctionProxyBid> findMine(long bidderId, long auctionId) {
        AuctionSession session = loadSession(auctionId);
        requireParticipant(session, bidderId);
        return proxyRepository.findByAuctionAndBidder(auctionId, bidderId);
    }

    public Result upsert(long bidderId, long auctionId, String requestId, BigDecimal maxAmount) {
        return execute(bidderId, auctionId, requestId, AuctionBidCommandType.UPSERT_PROXY, maxAmount);
    }

    public Result disable(long bidderId, long auctionId, String requestId) {
        return execute(bidderId, auctionId, requestId, AuctionBidCommandType.DISABLE_PROXY, null);
    }

    private Result execute(long bidderId, long auctionId, String requestId,
                           AuctionBidCommandType type, BigDecimal maxAmount) {
        requireRequestId(requestId);
        AuctionSession session = loadSession(auctionId);
        requireParticipant(session, bidderId);
        requireOpen(session);
        AuctionProxyBid existingProxy = proxyRepository.findByAuctionAndBidder(auctionId, bidderId).orElse(null);
        if (type == AuctionBidCommandType.DISABLE_PROXY && existingProxy == null) {
            throw new BusinessException(AuctionErrorCode.PROXY_NOT_FOUND);
        }
        if (type == AuctionBidCommandType.UPSERT_PROXY) {
            requireAmount(maxAmount);
            if (existingProxy != null && maxAmount.compareTo(existingProxy.maxAmount()) < 0
                    && session.currentBidderId() != null && session.currentBidderId() == bidderId) {
                throw new BusinessException(AuctionErrorCode.PROXY_AMOUNT_INVALID,
                        "A leading proxy maximum cannot be reduced below the current public price");
            }
        }

        String payloadHash = payloadHash(type, maxAmount);
        AuctionBidCommandIdempotency.Request request = new AuctionBidCommandIdempotency.Request(
                bidderId, requestId, type, payloadHash);
        AuctionBidCommandIdempotency.Decision decision = new AuctionBidCommandIdempotency()
                .inspect(commandRepository.findByActorAndRequest(bidderId, requestId).orElse(null), request);
        if (decision.type() == AuctionBidCommandIdempotency.DecisionType.PAYLOAD_CONFLICT) {
            throw new BusinessException(AuctionErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (decision.type() == AuctionBidCommandIdempotency.DecisionType.IN_PROGRESS) {
            throw new BusinessException(AuctionErrorCode.BID_CONFLICT, "The same request is already processing");
        }
        if (decision.type() == AuctionBidCommandIdempotency.DecisionType.REPLAY) {
            AuctionBidCommand replay = decision.existing();
            return new Result(replay, List.of(), session, existingProxy, false);
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionBidCommandIdempotency idempotency = new AuctionBidCommandIdempotency();
        AuctionBidCommand processing = idempotency.start(idGenerator.nextId(), auctionId, request, now);
        AuctionProxyBidLifecycle lifecycle = new AuctionProxyBidLifecycle();
        AuctionProxyBid changedProxy = switch (type) {
            case UPSERT_PROXY -> existingProxy == null
                    ? lifecycle.create(session, idGenerator.nextId(), bidderId, maxAmount, nextPriority(auctionId), now)
                    : lifecycle.update(session, existingProxy, maxAmount, nextPriority(auctionId), now);
            case DISABLE_PROXY -> lifecycle.disable(session, existingProxy, now);
            default -> throw new IllegalStateException("unsupported proxy command");
        };
        List<AuctionProxyBid> effective = proxyRepository.findActiveByAuction(auctionId).stream()
                .filter(proxy -> existingProxy == null || proxy.id() != existingProxy.id())
                .toList();
        List<AuctionProxyBid> planningRules = changedProxy.status() == AuctionProxyBidStatus.ACTIVE
                ? concat(effective, changedProxy) : effective;
        AuctionBidCommandPlanner.Plan plan = new AuctionBidCommandPlanner().plan(
                session, planningRules, AuctionBidCommandPlanner.Command.proxyRuleChanged(type, bidderId));
        AuctionBidCommand completed = idempotency.complete(processing, plan, now);
        List<BidRecord> bids = plan.bids().stream().map(bid -> new BidRecord(
                idGenerator.nextId(), auctionId, bid.bidderId(), requestId, bid.source(), processing.id(),
                bid.amount(), bid.previousPrice(), bid.sequenceNo(), now)).toList();
        AuctionBidCommandTransaction.ProxyMutation mutation = existingProxy == null
                ? AuctionBidCommandTransaction.ProxyMutation.insert(changedProxy)
                : AuctionBidCommandTransaction.ProxyMutation.update(changedProxy);
        AuctionBidCommandTransaction.CommittedCommand committed;
        try {
            committed = commandTransaction.commit(
                    new AuctionBidCommandTransaction.CommitRequest(
                            processing, completed, mutation, bids, session.version()));
        } catch (AuctionBidCommandTransaction.BidConflictException exception) {
            throw new BusinessException(AuctionErrorCode.BID_CONFLICT);
        }
        AuctionSession latest = sessionRepository.findSessionById(auctionId).orElseThrow();
        return new Result(committed.command(), committed.bids(), latest, changedProxy,
                latest.endAt().isAfter(session.endAt()));
    }

    private AuctionSession loadSession(long auctionId) {
        if (auctionId <= 0) throw new BusinessException(AuctionErrorCode.AUCTION_INVALID);
        return sessionRepository.findSessionById(auctionId)
                .map(lifecycleService::advanceToCurrentState)
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.AUCTION_NOT_FOUND));
    }

    private void requireParticipant(AuctionSession session, long bidderId) {
        if (session.sellerId() == bidderId) throw new BusinessException(AuctionErrorCode.SELLER_CANNOT_PARTICIPATE);
        if (registrationRepository.findByAuctionAndBidder(session.id(), bidderId)
                .filter(registration -> registration.status() == AuctionRegistrationStatus.REGISTERED).isEmpty()) {
            throw new BusinessException(AuctionErrorCode.REGISTRATION_REQUIRED);
        }
    }

    private static void requireOpen(AuctionSession session) {
        if (session.status() != io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus.OPEN) {
            throw new BusinessException(AuctionErrorCode.AUCTION_STATE_CONFLICT,
                    "proxy bidding requires an open auction");
        }
    }

    private long nextPriority(long auctionId) {
        return proxyRepository.findActiveByAuction(auctionId).stream()
                .mapToLong(AuctionProxyBid::priority).max().orElse(0L) + 1L;
    }

    private static List<AuctionProxyBid> concat(List<AuctionProxyBid> first, AuctionProxyBid second) {
        java.util.ArrayList<AuctionProxyBid> all = new java.util.ArrayList<>(first);
        all.add(second);
        return List.copyOf(all);
    }

    private static void requireAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2
                || amount.compareTo(new BigDecimal("99999999999999999.99")) > 0) {
            throw new BusinessException(AuctionErrorCode.PROXY_AMOUNT_INVALID);
        }
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || !requestId.trim().matches("[A-Za-z0-9_-]{8,48}")) {
            throw new BusinessException(AuctionErrorCode.AUCTION_INVALID,
                    "requestId must contain 8 to 48 letters, digits, underscores, or hyphens");
        }
    }

    private static String payloadHash(AuctionBidCommandType type, BigDecimal amount) {
        String payload = type.name() + ":" + (amount == null ? "" : amount.toPlainString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record Result(AuctionBidCommand command, List<BidRecord> bids,
                         AuctionSession session, AuctionProxyBid proxyBid, boolean extended) {
        public Result { bids = List.copyOf(bids); }
    }
}

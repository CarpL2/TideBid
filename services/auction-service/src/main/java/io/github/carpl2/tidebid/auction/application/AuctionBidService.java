package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionBidTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.IdGenerator;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@Profile({"local-db", "nacos"})
public class AuctionBidService {

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,48}");

    private final AuctionSessionRepository sessionRepository;
    private final AuctionRegistrationRepository registrationRepository;
    private final AuctionSessionLifecycleService lifecycleService;
    private final AuctionBidTransaction bidTransaction;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public AuctionBidService(
            AuctionSessionRepository sessionRepository,
            AuctionRegistrationRepository registrationRepository,
            AuctionSessionLifecycleService lifecycleService,
            AuctionBidTransaction bidTransaction,
            IdGenerator idGenerator,
            Clock clock
    ) {
        this.sessionRepository = sessionRepository;
        this.registrationRepository = registrationRepository;
        this.lifecycleService = lifecycleService;
        this.bidTransaction = bidTransaction;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public BidRecord place(PlaceBidCommand command) {
        PlaceBidCommand validCommand = requireCommand(command);
        BigDecimal amount = AuctionBidPolicy.normalizeAmount(validCommand.amount());
        Optional<BidRecord> existing = sessionRepository.findBid(
                validCommand.bidderId(), validCommand.requestId()
        );
        if (existing.isPresent()) {
            return requireSamePayload(existing.orElseThrow(), validCommand.auctionId(), amount);
        }

        AuctionSession session = sessionRepository.findSessionById(validCommand.auctionId())
                .map(lifecycleService::advanceToCurrentState)
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.AUCTION_NOT_FOUND));
        AuctionRegistration registration = registrationRepository
                .findByAuctionAndBidder(session.id(), validCommand.bidderId())
                .orElse(null);
        Instant acceptedAt = now();
        AuctionBidPolicy.ValidatedBid validated = AuctionBidPolicy.validate(
                session, registration, validCommand.bidderId(), amount, acceptedAt
        );
        BidRecord candidate = new BidRecord(
                nextId(),
                session.id(),
                validCommand.bidderId(),
                validCommand.requestId(),
                validated.amount(),
                validated.previousPrice(),
                validated.sequenceNo(),
                acceptedAt
        );
        try {
            return bidTransaction.accept(candidate, validated.expectedVersion()).bid();
        } catch (AuctionBidTransaction.DuplicateBidException exception) {
            BidRecord concurrentWinner = sessionRepository
                    .findBid(validCommand.bidderId(), validCommand.requestId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Bid uniqueness conflict did not produce an idempotency record",
                            exception
                    ));
            return requireSamePayload(concurrentWinner, validCommand.auctionId(), amount);
        } catch (AuctionBidTransaction.BidConflictException exception) {
            Optional<BidRecord> concurrentWinner = sessionRepository.findBid(
                    validCommand.bidderId(), validCommand.requestId()
            );
            if (concurrentWinner.isPresent()) {
                return requireSamePayload(
                        concurrentWinner.orElseThrow(), validCommand.auctionId(), amount
                );
            }
            AuctionSession latest = sessionRepository.findSessionById(validCommand.auctionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Auction session disappeared after a bid conflict",
                            exception
                    ));
            throw new AuctionBidConflictException(latest);
        }
    }

    private static BidRecord requireSamePayload(BidRecord existing, long auctionId, BigDecimal amount) {
        if (existing.auctionId() != auctionId || existing.amount().compareTo(amount) != 0) {
            throw new BusinessException(AuctionErrorCode.IDEMPOTENCY_CONFLICT);
        }
        return existing;
    }

    private static PlaceBidCommand requireCommand(PlaceBidCommand command) {
        if (command == null || command.bidderId() <= 0 || command.auctionId() <= 0) {
            throw new BusinessException(
                    AuctionErrorCode.AUCTION_INVALID,
                    "bidderId and auctionId must be positive"
            );
        }
        String normalizedRequestId = command.requestId() == null ? "" : command.requestId().trim();
        if (!SAFE_REQUEST_ID.matcher(normalizedRequestId).matches()) {
            throw new BusinessException(
                    AuctionErrorCode.AUCTION_INVALID,
                    "requestId must contain 8 to 48 letters, digits, underscores, or hyphens"
            );
        }
        return new PlaceBidCommand(
                command.bidderId(), command.auctionId(), normalizedRequestId, command.amount()
        );
    }

    private long nextId() {
        long id = idGenerator.nextId();
        if (id <= 0) {
            throw new IllegalStateException("idGenerator returned a non-positive id");
        }
        return id;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public record PlaceBidCommand(
            long bidderId,
            long auctionId,
            String requestId,
            BigDecimal amount
    ) {
    }
}

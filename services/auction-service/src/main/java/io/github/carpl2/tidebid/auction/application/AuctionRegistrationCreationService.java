package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationCreationTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.IdGenerator;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionRegistrationRecoveryProperties;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@Profile({"local-db", "nacos"})
public class AuctionRegistrationCreationService {

    private static final String REGISTRATION_NO_PREFIX = "REGISTRATION:";

    private final AuctionSessionRepository sessionRepository;
    private final AuctionItemRepository itemRepository;
    private final AuctionRegistrationRepository registrationRepository;
    private final AuctionRegistrationCreationTransaction creationTransaction;
    private final AuctionSessionLifecycleService lifecycleService;
    private final IdGenerator idGenerator;
    private final AuctionRegistrationRecoveryProperties recoveryProperties;
    private final Clock clock;

    public AuctionRegistrationCreationService(
            AuctionSessionRepository sessionRepository,
            AuctionItemRepository itemRepository,
            AuctionRegistrationRepository registrationRepository,
            AuctionRegistrationCreationTransaction creationTransaction,
            AuctionSessionLifecycleService lifecycleService,
            IdGenerator idGenerator,
            AuctionRegistrationRecoveryProperties recoveryProperties,
            Clock clock
    ) {
        this.sessionRepository = sessionRepository;
        this.itemRepository = itemRepository;
        this.registrationRepository = registrationRepository;
        this.creationTransaction = creationTransaction;
        this.lifecycleService = lifecycleService;
        this.idGenerator = idGenerator;
        this.recoveryProperties = recoveryProperties;
        this.clock = clock;
    }

    public RegistrationCreationResult createPending(CreateRegistrationCommand command) {
        if (command == null || command.bidderId() <= 0 || command.auctionId() <= 0) {
            throw new BusinessException(AuctionErrorCode.AUCTION_INVALID, "bidderId and auctionId must be positive");
        }
        AuctionSession session = sessionRepository.findSessionById(command.auctionId())
                .map(lifecycleService::advanceToCurrentState)
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.AUCTION_NOT_FOUND));
        AuctionItem item = itemRepository.findItemById(session.itemId())
                .filter(stored -> stored.reviewStatus() == AuctionItemReviewStatus.APPROVED)
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.AUCTION_NOT_FOUND));
        requireConsistentOwnership(item, session);
        if (session.sellerId() == command.bidderId()) {
            throw new BusinessException(AuctionErrorCode.SELLER_CANNOT_PARTICIPATE);
        }

        AuctionRegistration existing = registrationRepository
                .findByAuctionAndBidder(command.auctionId(), command.bidderId())
                .orElse(null);
        if (existing != null) {
            return new RegistrationCreationResult(existing, false);
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (session.status() != AuctionSessionStatus.SCHEDULED || !now.isBefore(session.startAt())) {
            throw new BusinessException(AuctionErrorCode.REGISTRATION_CLOSED);
        }
        long registrationId = nextId();
        AuctionRegistration pending = new AuctionRegistration(
                registrationId,
                REGISTRATION_NO_PREFIX + registrationId,
                session.id(),
                command.bidderId(),
                session.depositAmount(),
                AuctionRegistrationStatus.PENDING_HOLD,
                null,
                0,
                now.plus(recoveryProperties.initialRetryDelay()),
                null,
                null,
                null,
                null,
                0,
                now,
                now
        );
        try {
            return new RegistrationCreationResult(creationTransaction.createPending(pending), true);
        } catch (AuctionRegistrationCreationTransaction.DuplicateRegistrationException exception) {
            AuctionRegistration concurrentWinner = registrationRepository
                    .findByAuctionAndBidder(command.auctionId(), command.bidderId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Registration uniqueness conflict did not produce an existing registration",
                            exception
                    ));
            return new RegistrationCreationResult(concurrentWinner, false);
        }
    }

    private static void requireConsistentOwnership(AuctionItem item, AuctionSession session) {
        if (item.sellerId() != session.sellerId()) {
            throw new IllegalStateException("Auction item and session sellers do not match");
        }
    }

    private long nextId() {
        long id = idGenerator.nextId();
        if (id <= 0) {
            throw new IllegalStateException("idGenerator returned a non-positive id");
        }
        return id;
    }

    public record CreateRegistrationCommand(long bidderId, long auctionId) {
    }

    public record RegistrationCreationResult(AuctionRegistration registration, boolean created) {
        public RegistrationCreationResult {
            if (registration == null) {
                throw new IllegalArgumentException("registration must not be null");
            }
        }
    }
}

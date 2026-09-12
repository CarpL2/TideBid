package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionDraftTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@Profile({"local-db", "nacos"})
public class AuctionDraftUpdateService {

    private final AuctionItemRepository itemRepository;
    private final AuctionSessionRepository sessionRepository;
    private final AuctionDraftTransaction draftTransaction;
    private final AuctionDraftFieldsValidator fieldsValidator;
    private final Clock clock;

    public AuctionDraftUpdateService(
            AuctionItemRepository itemRepository,
            AuctionSessionRepository sessionRepository,
            AuctionDraftTransaction draftTransaction,
            AuctionDraftFieldsValidator fieldsValidator,
            Clock clock
    ) {
        this.itemRepository = itemRepository;
        this.sessionRepository = sessionRepository;
        this.draftTransaction = draftTransaction;
        this.fieldsValidator = fieldsValidator;
        this.clock = clock;
    }

    public AuctionDraftTransaction.UpdatedDraft update(UpdateDraftCommand command) {
        validateIdentityAndVersions(command);
        AuctionItem currentItem = itemRepository.findItemById(command.itemId())
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.ASSET_NOT_FOUND));
        if (currentItem.sellerId() != command.sellerId()) {
            throw new BusinessException(AuctionErrorCode.ASSET_ACCESS_DENIED);
        }
        if (currentItem.reviewStatus() != AuctionItemReviewStatus.DRAFT
                && currentItem.reviewStatus() != AuctionItemReviewStatus.REJECTED) {
            throw new BusinessException(AuctionErrorCode.ASSET_STATE_CONFLICT);
        }
        AuctionSession currentSession = sessionRepository.findSessionByItemId(command.itemId())
                .orElseThrow(() -> new IllegalStateException("Auction item has no session"));
        if (currentSession.sellerId() != command.sellerId()
                || currentSession.status() != AuctionSessionStatus.DRAFT) {
            throw new BusinessException(AuctionErrorCode.ASSET_STATE_CONFLICT);
        }
        if (currentItem.version() != command.expectedItemVersion()
                || currentSession.version() != command.expectedSessionVersion()) {
            throw new BusinessException(AuctionErrorCode.ASSET_STATE_CONFLICT, "Auction draft version is stale");
        }

        AuctionDraftFieldsValidator.ValidatedFields fields = fieldsValidator.validate(
                command.title(), command.description(), command.category(), command.itemCondition(),
                command.startPrice(), command.bidIncrement(), command.depositAmount(),
                command.startAt(), command.endAt()
        );
        Instant updatedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionItem updatedItem = new AuctionItem(
                currentItem.id(),
                currentItem.sellerId(),
                fields.title(),
                fields.description(),
                fields.category(),
                fields.itemCondition(),
                currentItem.reviewStatus(),
                currentItem.submissionVersion(),
                currentItem.version(),
                currentItem.submittedAt(),
                currentItem.approvedAt(),
                currentItem.createdAt(),
                updatedAt
        );
        AuctionSession updatedSession = new AuctionSession(
                currentSession.id(),
                currentSession.itemId(),
                currentSession.sellerId(),
                fields.startPrice(),
                fields.bidIncrement(),
                fields.depositAmount(),
                currentSession.currentPrice(),
                currentSession.currentBidderId(),
                currentSession.bidCount(),
                fields.startAt(),
                fields.endAt(),
                currentSession.status(),
                currentSession.version(),
                currentSession.createdAt(),
                updatedAt
        );
        try {
            return draftTransaction.update(updatedItem, updatedSession);
        } catch (AuctionDraftTransaction.DraftUpdateConflictException exception) {
            throw new BusinessException(AuctionErrorCode.ASSET_STATE_CONFLICT, exception.getMessage());
        }
    }

    private static void validateIdentityAndVersions(UpdateDraftCommand command) {
        if (command == null) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "command must not be null");
        }
        if (command.sellerId() <= 0 || command.itemId() <= 0) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "sellerId and itemId must be positive");
        }
        if (command.expectedItemVersion() < 0 || command.expectedSessionVersion() < 0) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "expected versions must not be negative");
        }
    }

    public record UpdateDraftCommand(
            long sellerId,
            long itemId,
            long expectedItemVersion,
            long expectedSessionVersion,
            String title,
            String description,
            String category,
            AuctionItemCondition itemCondition,
            BigDecimal startPrice,
            BigDecimal bidIncrement,
            BigDecimal depositAmount,
            Instant startAt,
            Instant endAt
    ) {
    }
}

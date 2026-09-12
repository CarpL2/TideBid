package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionReviewTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.IdGenerator;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionReview;
import io.github.carpl2.tidebid.auction.domain.AuctionReviewDecision;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@Profile({"local-db", "nacos"})
public class AuctionReviewService {

    private static final int MAXIMUM_COMMENT_LENGTH = 500;

    private final AuctionItemRepository itemRepository;
    private final AuctionSessionRepository sessionRepository;
    private final AuctionReviewTransaction reviewTransaction;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public AuctionReviewService(
            AuctionItemRepository itemRepository,
            AuctionSessionRepository sessionRepository,
            AuctionReviewTransaction reviewTransaction,
            IdGenerator idGenerator,
            Clock clock
    ) {
        this.itemRepository = itemRepository;
        this.sessionRepository = sessionRepository;
        this.reviewTransaction = reviewTransaction;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public AuctionReviewTransaction.ReviewedAuction review(ReviewCommand command) {
        validateCommand(command);
        String comment = normalizeComment(command.comment(), command.decision() == AuctionReviewDecision.REJECTED);
        AuctionItem item = itemRepository.findItemById(command.itemId())
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.ASSET_NOT_FOUND));
        if (item.sellerId() == command.reviewerId()) {
            throw new BusinessException(
                    AuctionErrorCode.ASSET_ACCESS_DENIED,
                    "Administrators cannot review their own auction assets"
            );
        }
        if (item.submissionVersion() != command.submissionVersion()) {
            throw new BusinessException(AuctionErrorCode.SUBMISSION_VERSION_CONFLICT);
        }
        if (item.reviewStatus() != AuctionItemReviewStatus.PENDING_REVIEW) {
            throw new BusinessException(AuctionErrorCode.ASSET_STATE_CONFLICT);
        }

        AuctionSession session = sessionRepository.findSessionByItemId(item.id())
                .orElseThrow(() -> new IllegalStateException("Auction item has no session"));
        if (session.sellerId() != item.sellerId() || session.status() != AuctionSessionStatus.DRAFT) {
            throw new BusinessException(AuctionErrorCode.ASSET_STATE_CONFLICT);
        }
        Instant reviewedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (command.decision() == AuctionReviewDecision.APPROVED && !session.startAt().isAfter(reviewedAt)) {
            throw new BusinessException(
                    AuctionErrorCode.AUCTION_TIME_INVALID,
                    "Auction start time must still be in the future when approved"
            );
        }

        AuctionReview review = new AuctionReview(
                nextId(), item.id(), item.submissionVersion(), command.reviewerId(),
                command.decision(), comment, reviewedAt
        );
        try {
            return switch (command.decision()) {
                case APPROVED -> reviewTransaction.approve(review, item.version(), session);
                case REJECTED -> reviewTransaction.reject(review, item.version(), session);
            };
        } catch (AuctionReviewTransaction.ReviewConflictException exception) {
            AuctionItem latest = itemRepository.findItemById(item.id())
                    .orElseThrow(() -> new BusinessException(AuctionErrorCode.ASSET_NOT_FOUND));
            if (latest.submissionVersion() != command.submissionVersion()) {
                throw new BusinessException(AuctionErrorCode.SUBMISSION_VERSION_CONFLICT);
            }
            throw new BusinessException(AuctionErrorCode.ASSET_STATE_CONFLICT, exception.getMessage());
        }
    }

    private long nextId() {
        long id = idGenerator.nextId();
        if (id <= 0) {
            throw new IllegalStateException("Generated review ID must be positive");
        }
        return id;
    }

    private static String normalizeComment(String value, boolean required) {
        if (value == null) {
            if (required) {
                throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "comment is required when rejecting");
            }
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            if (required) {
                throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "comment is required when rejecting");
            }
            return null;
        }
        if (normalized.length() > MAXIMUM_COMMENT_LENGTH) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "comment must not exceed 500 characters");
        }
        return normalized;
    }

    private static void validateCommand(ReviewCommand command) {
        if (command == null) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "command must not be null");
        }
        if (command.decision() == null) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "decision must not be null");
        }
        if (command.reviewerId() <= 0 || command.itemId() <= 0 || command.submissionVersion() <= 0) {
            throw new BusinessException(
                    AuctionErrorCode.ASSET_INVALID,
                    "reviewerId, itemId and submissionVersion must be positive"
            );
        }
    }

    public record ReviewCommand(
            long reviewerId,
            long itemId,
            int submissionVersion,
            AuctionReviewDecision decision,
            String comment
    ) {
    }
}

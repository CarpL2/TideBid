package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionReview;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuctionItemRepository {

    AuctionItem insertItem(AuctionItem item);

    Optional<AuctionItem> findItemById(long itemId);

    SellerItemPage findItemsBySeller(long sellerId, int offset, int limit);

    PendingReviewPage findPendingReviewItems(int offset, int limit);

    boolean updateEditableItem(AuctionItem item);

    boolean submitForReview(long itemId, long sellerId, long expectedVersion, Instant submittedAt);

    boolean approvePendingItem(
            long itemId,
            int expectedSubmissionVersion,
            long expectedVersion,
            Instant approvedAt
    );

    AuctionItemImage insertImage(AuctionItemImage image);

    Optional<AuctionItemImage> findImageByObjectKey(String objectKey);

    List<AuctionItemImage> findBoundImagesByItemIds(List<Long> itemIds);

    ImageBindingResult bindPendingImage(
            long imageId,
            long ownerId,
            long itemId,
            int sortOrder,
            Instant boundAt
    );

    List<AuctionItemImage> findPendingImageCleanupCandidates(
            Instant uploadExpiredAt,
            Instant createdBefore,
            int limit
    );

    boolean expirePendingImage(
            long imageId,
            Instant uploadExpiredAt,
            Instant createdBefore,
            Instant expiredAt
    );

    AuctionReview insertReview(AuctionReview review);

    Optional<AuctionReview> findReview(long itemId, int submissionVersion);

    Optional<AuctionReview> findLatestReview(long itemId);

    record SellerItemPage(List<AuctionItem> items, long total) {
        public SellerItemPage {
            items = List.copyOf(items);
            if (total < items.size()) {
                throw new IllegalArgumentException("total must not be below the returned item count");
            }
        }
    }

    record PendingReviewPage(List<AuctionItem> items, long total) {
        public PendingReviewPage {
            items = List.copyOf(items);
            if (total < items.size()) {
                throw new IllegalArgumentException("total must not be below the returned item count");
            }
        }
    }

    enum ImageBindingResult {
        BOUND,
        NOT_PENDING,
        POSITION_OCCUPIED
    }
}

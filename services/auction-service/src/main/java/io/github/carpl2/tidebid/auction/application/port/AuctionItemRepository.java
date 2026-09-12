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

    boolean updateEditableItem(AuctionItem item);

    AuctionItemImage insertImage(AuctionItemImage image);

    Optional<AuctionItemImage> findImageByObjectKey(String objectKey);

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

    enum ImageBindingResult {
        BOUND,
        NOT_PENDING,
        POSITION_OCCUPIED
    }
}

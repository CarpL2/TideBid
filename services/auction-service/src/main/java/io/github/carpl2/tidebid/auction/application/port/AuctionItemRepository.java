package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionReview;

import java.util.Optional;

public interface AuctionItemRepository {

    AuctionItem insertItem(AuctionItem item);

    Optional<AuctionItem> findItemById(long itemId);

    AuctionItemImage insertImage(AuctionItemImage image);

    Optional<AuctionItemImage> findImageByObjectKey(String objectKey);

    AuctionReview insertReview(AuctionReview review);

    Optional<AuctionReview> findReview(long itemId, int submissionVersion);
}

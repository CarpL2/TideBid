package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.port.AuctionDraftTransaction;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;

public record AuctionDraftResponse(
        String itemId,
        String auctionId,
        AuctionItemReviewStatus reviewStatus,
        AuctionSessionStatus sessionStatus,
        long itemVersion,
        long sessionVersion
) {
    static AuctionDraftResponse from(AuctionDraftTransaction.CreatedDraft source) {
        return from(source.item().id(), source.session().id(), source.item().reviewStatus(),
                source.session().status(), source.item().version(), source.session().version());
    }

    static AuctionDraftResponse from(AuctionDraftTransaction.UpdatedDraft source) {
        return from(source.item().id(), source.session().id(), source.item().reviewStatus(),
                source.session().status(), source.item().version(), source.session().version());
    }

    private static AuctionDraftResponse from(
            long itemId,
            long auctionId,
            AuctionItemReviewStatus reviewStatus,
            AuctionSessionStatus sessionStatus,
            long itemVersion,
            long sessionVersion
    ) {
        return new AuctionDraftResponse(
                Long.toString(itemId), Long.toString(auctionId), reviewStatus, sessionStatus,
                itemVersion, sessionVersion
        );
    }
}

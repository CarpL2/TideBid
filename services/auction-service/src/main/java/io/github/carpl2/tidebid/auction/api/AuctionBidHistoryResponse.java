package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionBidQueryService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AuctionBidHistoryResponse {

    private AuctionBidHistoryResponse() {
    }

    public record Page(
            String auctionId,
            int page,
            int size,
            long total,
            long totalPages,
            List<Item> items
    ) {
        static Page from(AuctionBidQueryService.BidPage source) {
            return new Page(
                    Long.toString(source.auctionId()),
                    source.page(),
                    source.size(),
                    source.total(),
                    source.totalPages(),
                    source.items().stream().map(Item::from).toList()
            );
        }
    }

    public record Item(
            String bidId,
            BigDecimal amount,
            BigDecimal previousPrice,
            long sequenceNo,
            Instant createdAt,
            boolean mine
    ) {
        static Item from(AuctionBidQueryService.BidView source) {
            return new Item(
                    Long.toString(source.bidId()),
                    source.amount(),
                    source.previousPrice(),
                    source.sequenceNo(),
                    source.createdAt(),
                    source.mine()
            );
        }
    }
}

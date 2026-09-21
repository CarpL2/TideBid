package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionProxyBidApplicationService;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBid;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBidStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AuctionProxyBidResponse {
    private AuctionProxyBidResponse() { }

    public record Detail(
            String auctionId,
            String proxyBidId,
            BigDecimal maxAmount,
            AuctionProxyBidStatus status,
            Instant updatedAt
    ) {
        static Detail from(AuctionProxyBid source) {
            return source == null ? null : new Detail(
                    Long.toString(source.auctionId()), Long.toString(source.id()), source.maxAmount(),
                    source.status(), source.updatedAt());
        }
    }

    public record Result(
            String auctionId,
            AuctionBidCommandStatus commandStatus,
            boolean leading,
            BigDecimal displayPrice,
            BigDecimal minimumNextBid,
            long bidCount,
            Instant endAt,
            boolean extended,
            List<String> bidIds,
            Detail proxyBid
    ) {
        static Result from(AuctionProxyBidApplicationService.Result source) {
            return new Result(
                    Long.toString(source.session().id()), source.command().status(),
                    Boolean.TRUE.equals(source.command().resultLeading()), source.session().displayPrice(),
                    source.session().minimumNextBid(), source.session().bidCount(), source.session().endAt(),
                    source.extended(), source.bids().stream().map(bid -> Long.toString(bid.id())).toList(),
                    Detail.from(source.proxyBid()));
        }
    }
}

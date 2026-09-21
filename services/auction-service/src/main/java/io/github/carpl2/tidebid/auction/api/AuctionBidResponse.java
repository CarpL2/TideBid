package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionBidService;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.auction.domain.BidSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AuctionBidResponse {

    private AuctionBidResponse() { }

    public record Accepted(
            String auctionId,
            BigDecimal requestedAmount,
            boolean leading,
            boolean outbidByProxy,
            BigDecimal displayPrice,
            BigDecimal minimumNextBid,
            long bidCount,
            Instant endAt,
            boolean extended,
            boolean replayed,
            long lastSequenceNo,
            List<PublicBid> acceptedBids
    ) {
        static Accepted from(AuctionBidService.Result source) {
            long resultSequence = source.command().lastSequenceNo() == null
                    ? source.session().bidCount() : source.command().lastSequenceNo();
            return new Accepted(
                    Long.toString(source.session().id()), source.requestedAmount(), source.leading(),
                    source.outbidByProxy(), source.command().resultPrice(),
                    source.command().resultPrice().add(source.session().bidIncrement()),
                    resultSequence, source.session().endAt(), source.extended(), source.replayed(),
                    resultSequence, source.bids().stream()
                            .map(bid -> PublicBid.from(bid, source.command().actorId())).toList()
            );
        }
    }

    public record PublicBid(
            String bidId,
            BigDecimal amount,
            BigDecimal previousPrice,
            long sequenceNo,
            BidSource source,
            boolean mine,
            Instant acceptedAt
    ) {
        static PublicBid from(BidRecord source, long actorId) {
            return new PublicBid(
                    Long.toString(source.id()), source.amount(), source.previousPrice(), source.sequenceNo(),
                    source.source(), source.bidderId() == actorId, source.createdAt());
        }
    }

    public record Conflict(
            String auctionId,
            BigDecimal currentPrice,
            BigDecimal minimumNextBid,
            long bidCount,
            long version,
            AuctionSessionStatus status
    ) {
        static Conflict from(
                io.github.carpl2.tidebid.auction.application.AuctionBidConflictException.ConflictSnapshot source
        ) {
            return new Conflict(
                    Long.toString(source.auctionId()), source.currentPrice(), source.minimumNextBid(),
                    source.bidCount(), source.version(), source.status());
        }
    }
}

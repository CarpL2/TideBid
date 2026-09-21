package io.github.carpl2.tidebid.realtime.infrastructure.client;

import io.github.carpl2.tidebid.contracts.RealtimeAuctionStatus;
import io.github.carpl2.tidebid.contracts.RealtimeBidView;
import io.github.carpl2.tidebid.contracts.RealtimeSnapshot;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.realtime.application.port.AuctionSnapshotClient;
import io.github.carpl2.tidebid.realtime.infrastructure.metrics.RealtimeMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("nacos")
@ConditionalOnProperty(prefix = "tidebid.realtime.auction-client", name = "enabled", havingValue = "true")
public class FeignAuctionSnapshotClient implements AuctionSnapshotClient {

    private final AuctionSnapshotFeignClient client;
    private final RealtimeMetrics metrics;

    public FeignAuctionSnapshotClient(AuctionSnapshotFeignClient client, RealtimeMetrics metrics) {
        this.client = client;
        this.metrics = metrics;
    }

    @Override
    public RealtimeSnapshot find(long auctionId, long afterSequenceNo, int limit, long userId, String traceId) {
        requirePositive(auctionId, "auctionId");
        requirePositive(userId, "userId");
        if (afterSequenceNo < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("snapshot window is invalid");
        }
        try {
            ApiResponse<AuctionSnapshotHttpResponse> response = client.find(
                    Long.toString(auctionId), afterSequenceNo, limit, Long.toString(userId), traceId);
            if (response == null || !ApiResponse.SUCCESS_CODE.equals(response.code()) || response.data() == null) {
                throw new IllegalStateException("Auction snapshot response was not successful");
            }
            RealtimeSnapshot snapshot = map(response.data(), auctionId);
            metrics.snapshotSucceeded();
            return snapshot;
        } catch (RuntimeException exception) {
            metrics.snapshotFailed();
            throw exception;
        }
    }

    private static RealtimeSnapshot map(AuctionSnapshotHttpResponse source, long expectedAuctionId) {
        long auctionId = parsePositive(source.auctionId(), "auctionId");
        if (auctionId != expectedAuctionId) {
            throw new IllegalStateException("Auction snapshot id does not match the request");
        }
        List<RealtimeBidView> bids = source.bids() == null ? List.of() : source.bids().stream()
                .map(bid -> new RealtimeBidView(
                        parsePositive(bid.bidId(), "bidId"), bid.amount(), bid.sequenceNo(),
                        bid.mine(), bid.acceptedAt()))
                .toList();
        return new RealtimeSnapshot(
                auctionId, RealtimeAuctionStatus.valueOf(source.status()), source.displayPrice(),
                source.minimumNextBid(), source.bidCount(), source.endAt(), source.closedAt(),
                source.extensionCount(), source.lastSequenceNo(), source.currentUserLeading(),
                source.currentUserHasProxy(), bids);
    }

    private static long parsePositive(String value, String name) {
        try {
            long parsed = Long.parseLong(value);
            requirePositive(parsed, name);
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(name + " is not a positive integer", exception);
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}

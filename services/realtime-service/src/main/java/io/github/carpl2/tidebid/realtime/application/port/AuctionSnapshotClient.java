package io.github.carpl2.tidebid.realtime.application.port;

import io.github.carpl2.tidebid.contracts.RealtimeSnapshot;

public interface AuctionSnapshotClient {
    RealtimeSnapshot find(long auctionId, long afterSequenceNo, int limit, long userId, String traceId);
}

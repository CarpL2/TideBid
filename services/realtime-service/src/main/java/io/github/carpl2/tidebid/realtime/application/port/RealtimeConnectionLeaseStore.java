package io.github.carpl2.tidebid.realtime.application.port;

import io.github.carpl2.tidebid.realtime.application.service.RealtimeConnectionLeaseStoreUnavailableException;

import java.time.Duration;

public interface RealtimeConnectionLeaseStore {

    boolean acquire(long userId, String connectionId, int maxConnections, Duration ttl)
            throws RealtimeConnectionLeaseStoreUnavailableException;

    void release(long userId, String connectionId) throws RealtimeConnectionLeaseStoreUnavailableException;
}

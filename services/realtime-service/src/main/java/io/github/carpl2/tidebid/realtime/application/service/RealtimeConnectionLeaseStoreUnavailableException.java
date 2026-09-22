package io.github.carpl2.tidebid.realtime.application.service;

public class RealtimeConnectionLeaseStoreUnavailableException extends RuntimeException {

    public RealtimeConnectionLeaseStoreUnavailableException(Throwable cause) {
        super("Realtime connection lease store is unavailable", cause);
    }
}

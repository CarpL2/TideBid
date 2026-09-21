package io.github.carpl2.tidebid.realtime.application.service;

public final class RealtimeTicketStoreUnavailableException extends RuntimeException {

    public RealtimeTicketStoreUnavailableException(Throwable cause) {
        super("Realtime ticket store is unavailable", cause);
    }
}

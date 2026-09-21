package io.github.carpl2.tidebid.realtime.application.port;

import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketIdentity;

import java.time.Duration;
import java.util.Optional;

public interface RealtimeTicketStore {

    TicketRateLimitDecision acquireRateLimit(long userId, String sourceIp, Duration window, int maxRequests);

    void save(String digest, RealtimeTicketIdentity identity, Duration ttl);

    Optional<RealtimeTicketIdentity> consume(String digest);

    record TicketRateLimitDecision(boolean allowed, Duration retryAfter) {
        public TicketRateLimitDecision {
            if (retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter must not be negative");
            }
        }

        public static TicketRateLimitDecision allow() {
            return new TicketRateLimitDecision(true, Duration.ZERO);
        }

        public static TicketRateLimitDecision reject(Duration retryAfter) {
            return new TicketRateLimitDecision(false, retryAfter);
        }
    }
}

package io.github.carpl2.tidebid.gateway.ratelimit;

import java.time.Duration;
import java.util.Objects;

record RateLimitDecision(boolean allowed, Duration retryAfter) {

    RateLimitDecision {
        retryAfter = Objects.requireNonNull(retryAfter, "retryAfter must not be null");
        if (retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }
        if (allowed && !retryAfter.isZero()) {
            throw new IllegalArgumentException("an allowed request cannot have a retry delay");
        }
        if (!allowed && retryAfter.isZero()) {
            throw new IllegalArgumentException("a rejected request must have a retry delay");
        }
    }

    static RateLimitDecision allow() {
        return new RateLimitDecision(true, Duration.ZERO);
    }

    static RateLimitDecision reject(Duration retryAfter) {
        return new RateLimitDecision(false, retryAfter);
    }
}

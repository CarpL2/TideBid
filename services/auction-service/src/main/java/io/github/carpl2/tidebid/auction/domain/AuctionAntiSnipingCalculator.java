package io.github.carpl2.tidebid.auction.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Calculates a bounded anti-sniping extension without mutating persistence state. */
public final class AuctionAntiSnipingCalculator {

    public Result calculate(
            AuctionSession session,
            Instant acceptedAt,
            boolean publicPriceChanged,
            Duration window,
            Duration extension,
            Duration maxTotalExtension
    ) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        requirePositive(window, "window");
        requirePositive(extension, "extension");
        requirePositive(maxTotalExtension, "maxTotalExtension");
        if (maxTotalExtension.compareTo(extension) < 0) {
            throw new IllegalArgumentException("maxTotalExtension must be at least extension");
        }
        if (session.status() != AuctionSessionStatus.OPEN) {
            return unchanged(session);
        }
        if (!publicPriceChanged || acceptedAt.isAfter(session.endAt())) {
            return unchanged(session);
        }
        Duration remaining = Duration.between(acceptedAt, session.endAt());
        if (remaining.compareTo(window) > 0) {
            return unchanged(session);
        }

        Instant candidateEndAt = max(session.endAt(), acceptedAt.plus(extension));
        Instant upperBound = session.originalEndAt().plus(maxTotalExtension);
        Instant newEndAt = candidateEndAt.isAfter(upperBound) ? upperBound : candidateEndAt;
        if (!newEndAt.isAfter(session.endAt())) {
            return unchanged(session);
        }
        int newExtensionCount;
        try {
            newExtensionCount = Math.addExact(session.extensionCount(), 1);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("extension count overflow", exception);
        }
        return new Result(true, session.endAt(), newEndAt, newExtensionCount);
    }

    private static Result unchanged(AuctionSession session) {
        return new Result(false, session.endAt(), session.endAt(), session.extensionCount());
    }

    private static Instant max(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public record Result(
            boolean extended,
            Instant previousEndAt,
            Instant endAt,
            int extensionCount
    ) {
        public Result {
            previousEndAt = Objects.requireNonNull(previousEndAt, "previousEndAt must not be null");
            endAt = Objects.requireNonNull(endAt, "endAt must not be null");
            if (endAt.isBefore(previousEndAt)) {
                throw new IllegalArgumentException("endAt must not move backwards");
            }
            if (extensionCount < 0) {
                throw new IllegalArgumentException("extensionCount must not be negative");
            }
            if (extended != (endAt.isAfter(previousEndAt))) {
                throw new IllegalArgumentException("extended flag must match endAt change");
            }
        }
    }
}

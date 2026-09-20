package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionBidCommandTransaction;

import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Bounds CAS retries for a command. Each attempt must reload and re-plan from current state;
 * this class only controls retry count and the final snapshot fallback.
 */
public final class AuctionBidCasRetryCoordinator {

    private final int maximumAttempts;

    public AuctionBidCasRetryCoordinator(int maximumAttempts) {
        if (maximumAttempts < 1 || maximumAttempts > 5) {
            throw new IllegalArgumentException("maximumAttempts must be between 1 and 5");
        }
        this.maximumAttempts = maximumAttempts;
    }

    public <C, S> Outcome<C, S> execute(
            IntFunction<C> reloadPlanAndCommit,
            Supplier<S> latestSnapshot
    ) {
        Objects.requireNonNull(reloadPlanAndCommit, "reloadPlanAndCommit must not be null");
        Objects.requireNonNull(latestSnapshot, "latestSnapshot must not be null");
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            try {
                C committed = Objects.requireNonNull(
                        reloadPlanAndCommit.apply(attempt), "committed result must not be null");
                return Outcome.committed(committed, attempt);
            } catch (AuctionBidCommandTransaction.BidConflictException conflict) {
                if (attempt == maximumAttempts) {
                    return Outcome.exhausted(
                            Objects.requireNonNull(latestSnapshot.get(), "latest snapshot must not be null"),
                            attempt);
                }
            }
        }
        throw new IllegalStateException("CAS retry loop terminated unexpectedly");
    }

    public int maximumAttempts() {
        return maximumAttempts;
    }

    public enum Status {
        COMMITTED,
        EXHAUSTED
    }

    public record Outcome<C, S>(Status status, C committed, S latestSnapshot, int attempts) {
        public Outcome {
            status = Objects.requireNonNull(status, "status must not be null");
            if (attempts < 1) {
                throw new IllegalArgumentException("attempts must be positive");
            }
            if (status == Status.COMMITTED) {
                if (committed == null || latestSnapshot != null) {
                    throw new IllegalArgumentException("committed outcome must contain only committed result");
                }
            } else if (committed != null || latestSnapshot == null) {
                throw new IllegalArgumentException("exhausted outcome must contain only latest snapshot");
            }
        }

        private static <C, S> Outcome<C, S> committed(C committed, int attempts) {
            return new Outcome<>(Status.COMMITTED, committed, null, attempts);
        }

        private static <C, S> Outcome<C, S> exhausted(S latestSnapshot, int attempts) {
            return new Outcome<>(Status.EXHAUSTED, null, latestSnapshot, attempts);
        }
    }
}

package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionBidCommandTransaction;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionBidCasRetryCoordinatorTest {

    @Test
    void reloadsAndReplansAfterAConflictUntilTheSecondAttemptCommits() {
        AuctionBidCasRetryCoordinator coordinator = new AuctionBidCasRetryCoordinator(3);
        AtomicInteger calls = new AtomicInteger();

        var outcome = coordinator.execute(attempt -> {
            assertThat(attempt).isEqualTo(calls.incrementAndGet());
            if (attempt == 1) {
                throw new AuctionBidCommandTransaction.BidConflictException();
            }
            return "committed-attempt-" + attempt;
        }, () -> "latest-snapshot");

        assertThat(outcome.status()).isEqualTo(AuctionBidCasRetryCoordinator.Status.COMMITTED);
        assertThat(outcome.committed()).isEqualTo("committed-attempt-2");
        assertThat(outcome.latestSnapshot()).isNull();
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(calls).hasValue(2);
    }

    @Test
    void stopsAtTheConfiguredLimitAndReturnsOneLatestSnapshot() {
        AuctionBidCasRetryCoordinator coordinator = new AuctionBidCasRetryCoordinator(3);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger snapshotReads = new AtomicInteger();

        var outcome = coordinator.execute(attempt -> {
            attempts.incrementAndGet();
            throw new AuctionBidCommandTransaction.BidConflictException();
        }, () -> {
            snapshotReads.incrementAndGet();
            return "latest-after-conflicts";
        });

        assertThat(outcome.status()).isEqualTo(AuctionBidCasRetryCoordinator.Status.EXHAUSTED);
        assertThat(outcome.committed()).isNull();
        assertThat(outcome.latestSnapshot()).isEqualTo("latest-after-conflicts");
        assertThat(outcome.attempts()).isEqualTo(3);
        assertThat(attempts).hasValue(3);
        assertThat(snapshotReads).hasValue(1);
    }

    @Test
    void doesNotRetryUnexpectedFailuresOrReadFallbackSnapshot() {
        AuctionBidCasRetryCoordinator coordinator = new AuctionBidCasRetryCoordinator(3);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger snapshots = new AtomicInteger();

        assertThatThrownBy(() -> coordinator.execute(attempt -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("database unavailable");
        }, () -> {
            snapshots.incrementAndGet();
            return "latest";
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        assertThat(attempts).hasValue(1);
        assertThat(snapshots).hasValue(0);
    }

    @Test
    void validatesTheRetryLimitAndOutcomeInvariants() {
        assertThatThrownBy(() -> new AuctionBidCasRetryCoordinator(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuctionBidCasRetryCoordinator(6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuctionBidCasRetryCoordinator(1)
                .execute(null, () -> "snapshot"))
                .isInstanceOf(NullPointerException.class);
    }
}

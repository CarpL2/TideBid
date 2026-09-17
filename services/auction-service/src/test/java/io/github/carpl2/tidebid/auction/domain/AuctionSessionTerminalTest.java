package io.github.carpl2.tidebid.auction.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionSessionTerminalTest {

    private static final Instant CREATED = Instant.parse("2026-09-17T00:00:00Z");
    private static final Instant START = CREATED.plusSeconds(60);
    private static final Instant END = START.plusSeconds(3600);
    private static final Instant CLOSED = END.plusMillis(1);

    @Test
    void acceptsConsistentSoldAndUnsoldSnapshots() {
        AuctionSession sold = session(
                new BigDecimal("130.00"), 88L, 3, AuctionSessionStatus.CLOSED_SOLD,
                88L, 901L, new BigDecimal("130.00"));
        AuctionSession unsold = session(
                null, null, 0, AuctionSessionStatus.CLOSED_UNSOLD, null, null, null);

        assertThat(sold.finalPrice()).isEqualByComparingTo("130.00");
        assertThat(unsold.closedAt()).isEqualTo(CLOSED);
    }

    @Test
    void rejectsAWinningSnapshotThatDoesNotMatchTheCurrentBid() {
        assertThatThrownBy(() -> session(
                new BigDecimal("130.00"), 88L, 3, AuctionSessionStatus.CLOSED_SOLD,
                89L, 901L, new BigDecimal("130.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("winning bid");
    }

    private static AuctionSession session(
            BigDecimal currentPrice,
            Long currentBidderId,
            long bidCount,
            AuctionSessionStatus status,
            Long winnerId,
            Long winningBidId,
            BigDecimal finalPrice
    ) {
        return new AuctionSession(
                1L, 2L, 3L, new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("50.00"), currentPrice, currentBidderId, bidCount,
                START, END, status, winnerId, winningBidId, finalPrice, CLOSED,
                4L, CREATED, CLOSED);
    }
}

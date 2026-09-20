package io.github.carpl2.tidebid.auction.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionAntiSnipingCalculatorTest {

    private static final Instant START = Instant.parse("2026-09-21T02:00:00Z");
    private static final Instant ORIGINAL_END = START.plusSeconds(3600);
    private static final AuctionAntiSnipingCalculator CALCULATOR = new AuctionAntiSnipingCalculator();

    @Test
    void extendsAChangedPublicPriceInsideTheWindowToAtLeastAcceptedPlusExtension() {
        AuctionSession session = session(ORIGINAL_END, ORIGINAL_END, 0);

        var result = CALCULATOR.calculate(
                session, ORIGINAL_END.minusSeconds(30), true,
                Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(300)
        );

        assertThat(result.extended()).isTrue();
        assertThat(result.previousEndAt()).isEqualTo(ORIGINAL_END);
        assertThat(result.endAt()).isEqualTo(ORIGINAL_END.plusSeconds(30));
        assertThat(result.extensionCount()).isEqualTo(1);
    }

    @Test
    void doesNotExtendOutsideWindowOrWhenPublicStateDidNotChange() {
        AuctionSession session = session(ORIGINAL_END, ORIGINAL_END, 0);

        assertThat(CALCULATOR.calculate(session, ORIGINAL_END.minusSeconds(61), true,
                Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(300)).extended()).isFalse();
        assertThat(CALCULATOR.calculate(session, ORIGINAL_END.minusSeconds(30), false,
                Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(300)).extended()).isFalse();
    }

    @Test
    void capsNewEndAtOriginalEndPlusMaximumTotalExtension() {
        Instant alreadyExtendedEnd = ORIGINAL_END.plusSeconds(250);
        AuctionSession session = session(alreadyExtendedEnd, ORIGINAL_END, 5);

        var result = CALCULATOR.calculate(
                session, alreadyExtendedEnd.minusSeconds(10), true,
                Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(300)
        );

        assertThat(result.extended()).isTrue();
        assertThat(result.endAt()).isEqualTo(ORIGINAL_END.plusSeconds(300));
        assertThat(result.extensionCount()).isEqualTo(6);
    }

    @Test
    void reachingTheTotalExtensionLimitDoesNotCreateAnotherExtension() {
        Instant cappedEnd = ORIGINAL_END.plusSeconds(300);
        AuctionSession session = session(cappedEnd, ORIGINAL_END, 5);

        var result = CALCULATOR.calculate(
                session, cappedEnd.minusSeconds(10), true,
                Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(300)
        );

        assertThat(result.extended()).isFalse();
        assertThat(result.endAt()).isEqualTo(cappedEnd);
        assertThat(result.extensionCount()).isEqualTo(5);
    }

    @Test
    void endBoundaryIsInsideWindowAndAfterEndBoundaryIsNotAccepted() {
        AuctionSession session = session(ORIGINAL_END, ORIGINAL_END, 0);

        assertThat(CALCULATOR.calculate(session, ORIGINAL_END.minusSeconds(59), true,
                Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(300)).extended()).isTrue();
        assertThat(CALCULATOR.calculate(session, ORIGINAL_END.plusNanos(1), true,
                Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(300)).extended()).isFalse();
    }

    @Test
    void nonOpenAuctionsAndInvalidConfigurationAreRejectedOrUnchanged() {
        AuctionSession closed = new AuctionSession(
                1L, 2L, 3L, new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("50.00"), null, null, 0,
                START, ORIGINAL_END, AuctionSessionStatus.CLOSED_UNSOLD,
                null, null, null, ORIGINAL_END, 0L, START.minusSeconds(1), START
        );
        assertThat(CALCULATOR.calculate(closed, ORIGINAL_END.minusSeconds(10), true,
                Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(300)).extended()).isFalse();
        assertThatThrownBy(() -> CALCULATOR.calculate(
                session(ORIGINAL_END, ORIGINAL_END, 0), ORIGINAL_END, true,
                Duration.ZERO, Duration.ofSeconds(60), Duration.ofSeconds(300)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CALCULATOR.calculate(
                session(ORIGINAL_END, ORIGINAL_END, 0), ORIGINAL_END, true,
                Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(30)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static AuctionSession session(Instant endAt, Instant originalEndAt, int extensionCount) {
        return new AuctionSession(
                1L, 2L, 3L, new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("50.00"), null, null, 0,
                START, endAt, originalEndAt, extensionCount, AuctionSessionStatus.OPEN,
                null, null, null, null, 0L, START.minusSeconds(1), START
        );
    }
}

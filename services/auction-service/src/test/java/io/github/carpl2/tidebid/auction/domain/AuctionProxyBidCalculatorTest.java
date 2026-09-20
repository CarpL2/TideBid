package io.github.carpl2.tidebid.auction.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionProxyBidCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-09-20T02:00:00Z");
    private static final AuctionProxyBidCalculator CALCULATOR = new AuctionProxyBidCalculator();

    @Test
    void leavesAnEmptyAuctionWithoutProxyRulesUnchanged() {
        var result = CALCULATOR.calculate(emptySession(), List.of());

        assertThat(result.leadingBidderId()).isNull();
        assertThat(result.leadingProxyBidId()).isNull();
        assertThat(result.displayPrice()).isEqualByComparingTo("100.00");
        assertThat(result.minimumNextBid()).isEqualByComparingTo("100.00");
        assertThat(result.hasAcceptedBid()).isFalse();
        assertThat(result.priceChanged()).isFalse();
        assertThat(result.leaderChanged()).isFalse();
    }

    @Test
    void aSingleProxyUsesOnlyTheOpeningPriceInsteadOfExposingItsMaximum() {
        var result = CALCULATOR.calculate(emptySession(), List.of(activeProxy(11, 101, "500.00", 1)));

        assertThat(result.leadingBidderId()).isEqualTo(101L);
        assertThat(result.leadingProxyBidId()).isEqualTo(11L);
        assertThat(result.displayPrice()).isEqualByComparingTo("100.00");
        assertThat(result.minimumNextBid()).isEqualByComparingTo("110.00");
        assertThat(result.hasAcceptedBid()).isTrue();
        assertThat(result.priceChanged()).isFalse();
        assertThat(result.leaderChanged()).isTrue();
    }

    @Test
    void aProxyMustReachTheNextLegalPriceToOvertakeAManualLeader() {
        AuctionSession session = sessionWithBid(101, "120.00", 1);

        var insufficient = CALCULATOR.calculate(session, List.of(activeProxy(12, 102, "129.99", 2)));
        var sufficient = CALCULATOR.calculate(session, List.of(activeProxy(12, 102, "130.00", 2)));

        assertThat(insufficient.leadingBidderId()).isEqualTo(101L);
        assertThat(insufficient.displayPrice()).isEqualByComparingTo("120.00");
        assertThat(insufficient.priceChanged()).isFalse();
        assertThat(sufficient.leadingBidderId()).isEqualTo(102L);
        assertThat(sufficient.displayPrice()).isEqualByComparingTo("130.00");
        assertThat(sufficient.minimumNextBid()).isEqualByComparingTo("140.00");
    }

    @Test
    void twoProxiesChargeOnlyOneIncrementAboveTheRunnerUpMaximum() {
        var result = CALCULATOR.calculate(emptySession(), List.of(
                activeProxy(11, 101, "500.00", 1),
                activeProxy(12, 102, "300.00", 2)
        ));

        assertThat(result.leadingBidderId()).isEqualTo(101L);
        assertThat(result.displayPrice()).isEqualByComparingTo("310.00");
        assertThat(result.displayPrice()).isLessThan(new BigDecimal("500.00"));
    }

    @Test
    void equalProxyMaximumsAreWonByTheEarlierPriorityAtThatMaximum() {
        var result = CALCULATOR.calculate(emptySession(), List.of(
                activeProxy(11, 101, "500.00", 20),
                activeProxy(12, 102, "500.00", 10)
        ));

        assertThat(result.leadingBidderId()).isEqualTo(102L);
        assertThat(result.leadingProxyBidId()).isEqualTo(12L);
        assertThat(result.displayPrice()).isEqualByComparingTo("500.00");
    }

    @Test
    void anExistingProxyLeaderNeverFallsBackToAStaleLowerRunnerUp() {
        AuctionSession session = sessionWithBid(101, "400.00", 8);

        var result = CALCULATOR.calculate(session, List.of(
                activeProxy(11, 101, "500.00", 1),
                activeProxy(12, 102, "200.00", 2)
        ));

        assertThat(result.leadingBidderId()).isEqualTo(101L);
        assertThat(result.displayPrice()).isEqualByComparingTo("400.00");
        assertThat(result.priceChanged()).isFalse();
        assertThat(result.leaderChanged()).isFalse();
    }

    @Test
    void loweringAProxyBelowItsAcceptedBidKeepsTheBidWithoutPretendingTheRuleBacksIt() {
        AuctionSession session = sessionWithBid(101, "400.00", 8);

        var result = CALCULATOR.calculate(session, List.of(activeProxy(11, 101, "300.00", 2)));

        assertThat(result.leadingBidderId()).isEqualTo(101L);
        assertThat(result.leadingProxyBidId()).isNull();
        assertThat(result.displayPrice()).isEqualByComparingTo("400.00");
        assertThat(result.priceChanged()).isFalse();
    }

    @Test
    void theRunnerUpCanPushTheWinnerExactlyToItsMaximumButNeverPastIt() {
        var result = CALCULATOR.calculate(emptySession(), List.of(
                activeProxy(11, 101, "305.00", 1),
                activeProxy(12, 102, "300.00", 2)
        ));

        assertThat(result.leadingBidderId()).isEqualTo(101L);
        assertThat(result.displayPrice()).isEqualByComparingTo("305.00");
        assertThat(result.displayPrice()).isLessThanOrEqualTo(new BigDecimal("305.00"));
        assertThat(result.displayPrice().scale()).isEqualTo(2);
        assertThat(result.minimumNextBid().scale()).isEqualTo(2);
    }

    @Test
    void disabledRulesDoNotCompete() {
        AuctionProxyBid disabled = new AuctionProxyBid(
                13L, 1L, 103L, new BigDecimal("900.00"), AuctionProxyBidStatus.DISABLED,
                1L, 1L, NOW, NOW.minusSeconds(60), NOW
        );

        var result = CALCULATOR.calculate(emptySession(), List.of(
                disabled,
                activeProxy(11, 101, "500.00", 2)
        ));

        assertThat(result.leadingBidderId()).isEqualTo(101L);
        assertThat(result.displayPrice()).isEqualByComparingTo("100.00");
    }

    @Test
    void rejectsForeignOrDuplicateActiveProxyState() {
        AuctionProxyBid foreign = new AuctionProxyBid(
                14L, 2L, 104L, new BigDecimal("500.00"), AuctionProxyBidStatus.ACTIVE,
                1L, 0L, null, NOW, NOW
        );

        assertThatThrownBy(() -> CALCULATOR.calculate(emptySession(), List.of(foreign)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another auction");
        assertThatThrownBy(() -> CALCULATOR.calculate(emptySession(), List.of(
                activeProxy(11, 101, "500.00", 1),
                activeProxy(12, 101, "600.00", 2)
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate active proxy bidders");
    }

    @Test
    void refusesToCalculateAgainstANonOpenAuction() {
        AuctionSession scheduled = new AuctionSession(
                1L, 2L, 3L, new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("50.00"), null, null, 0,
                NOW.plusSeconds(60), NOW.plusSeconds(3600), AuctionSessionStatus.SCHEDULED,
                0L, NOW, NOW
        );

        assertThatThrownBy(() -> CALCULATOR.calculate(scheduled, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("open auction");
    }

    private static AuctionSession emptySession() {
        return new AuctionSession(
                1L, 2L, 3L, new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("50.00"), null, null, 0,
                NOW.minusSeconds(3600), NOW.plusSeconds(3600), AuctionSessionStatus.OPEN,
                0L, NOW.minusSeconds(7200), NOW
        );
    }

    private static AuctionSession sessionWithBid(long bidderId, String currentPrice, long bidCount) {
        return new AuctionSession(
                1L, 2L, 3L, new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("50.00"), new BigDecimal(currentPrice), bidderId, bidCount,
                NOW.minusSeconds(3600), NOW.plusSeconds(3600), AuctionSessionStatus.OPEN,
                5L, NOW.minusSeconds(7200), NOW
        );
    }

    private static AuctionProxyBid activeProxy(
            long id,
            long bidderId,
            String maximum,
            long priority
    ) {
        return new AuctionProxyBid(
                id, 1L, bidderId, new BigDecimal(maximum), AuctionProxyBidStatus.ACTIVE,
                priority, 0L, null, NOW, NOW
        );
    }
}

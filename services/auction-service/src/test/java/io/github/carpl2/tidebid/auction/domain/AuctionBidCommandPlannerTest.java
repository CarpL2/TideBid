package io.github.carpl2.tidebid.auction.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionBidCommandPlannerTest {

    private static final Instant NOW = Instant.parse("2026-09-20T04:00:00Z");
    private static final AuctionBidCommandPlanner PLANNER = new AuctionBidCommandPlanner();

    @Test
    void manualBidWithoutProxyProducesOneManualRecord() {
        var plan = PLANNER.plan(emptySession(), List.of(), manual(101, "130.00"));

        assertThat(plan.bids()).containsExactly(
                bid(101, BidSource.MANUAL, "130.00", null, 1)
        );
        assertThat(plan.leadingBidderId()).isEqualTo(101L);
        assertThat(plan.leadingProxyBidId()).isNull();
        assertThat(plan.displayPrice()).isEqualByComparingTo("130.00");
        assertThat(plan.minimumNextBid()).isEqualByComparingTo("140.00");
    }

    @Test
    void higherDefendingProxyAnswersAManualBidWithASecondContiguousRecord() {
        AuctionSession session = sessionWithBid(201, "120.00", 7);

        var plan = PLANNER.plan(session, List.of(proxy(11, 201, "500.00", 1)), manual(101, "200.00"));

        assertThat(plan.bids()).containsExactly(
                bid(101, BidSource.MANUAL, "200.00", "120.00", 8),
                bid(201, BidSource.PROXY, "210.00", "200.00", 9)
        );
        assertThat(plan.leadingBidderId()).isEqualTo(201L);
        assertThat(plan.leadingProxyBidId()).isEqualTo(11L);
    }

    @Test
    void manualBidAboveEveryOtherProxyWinsWithoutAnExtraRecord() {
        var plan = PLANNER.plan(
                sessionWithBid(201, "120.00", 1),
                List.of(proxy(11, 201, "300.00", 1)),
                manual(101, "350.00")
        );

        assertThat(plan.bids()).containsExactly(
                bid(101, BidSource.MANUAL, "350.00", "120.00", 2)
        );
        assertThat(plan.leadingBidderId()).isEqualTo(101L);
    }

    @Test
    void equalManualAmountKeepsEarlierProxyLeaderWithoutDuplicatePrice() {
        var plan = PLANNER.plan(
                sessionWithBid(201, "120.00", 1),
                List.of(proxy(11, 201, "300.00", 1)),
                manual(101, "300.00")
        );

        assertThat(plan.bids()).containsExactly(
                bid(201, BidSource.PROXY, "300.00", "120.00", 2)
        );
        assertThat(plan.leadingBidderId()).isEqualTo(201L);
    }

    @Test
    void aFirstProxyRuleCreatesOneOpeningBidWithoutExposingItsMaximum() {
        var plan = PLANNER.plan(
                emptySession(),
                List.of(proxy(11, 101, "500.00", 1)),
                proxyChange(AuctionBidCommandType.UPSERT_PROXY, 101)
        );

        assertThat(plan.bids()).containsExactly(
                bid(101, BidSource.PROXY, "100.00", null, 1)
        );
        assertThat(plan.displayPrice()).isEqualByComparingTo("100.00");
        assertThat(plan.toString()).doesNotContain("500.00");
    }

    @Test
    void higherProxyChallengerProducesOnlyTheFinalNecessaryPrice() {
        var plan = PLANNER.plan(
                sessionWithBid(201, "120.00", 4),
                List.of(proxy(11, 201, "300.00", 1), proxy(12, 101, "500.00", 2)),
                proxyChange(AuctionBidCommandType.UPSERT_PROXY, 101)
        );

        assertThat(plan.bids()).containsExactly(
                bid(101, BidSource.PROXY, "310.00", "120.00", 5)
        );
        assertThat(plan.leadingBidderId()).isEqualTo(101L);
        assertThat(plan.leadingProxyBidId()).isEqualTo(12L);
    }

    @Test
    void lowerProxyChallengerAndDefenderProduceAtMostTwoRecords() {
        var plan = PLANNER.plan(
                sessionWithBid(201, "120.00", 4),
                List.of(proxy(11, 201, "500.00", 1), proxy(12, 101, "300.00", 2)),
                proxyChange(AuctionBidCommandType.UPSERT_PROXY, 101)
        );

        assertThat(plan.bids()).containsExactly(
                bid(101, BidSource.PROXY, "300.00", "120.00", 5),
                bid(201, BidSource.PROXY, "310.00", "300.00", 6)
        );
        assertThat(plan.bids()).hasSizeLessThanOrEqualTo(2);
        assertThat(plan.leadingBidderId()).isEqualTo(201L);
    }

    @Test
    void equalProxyMaximumProducesOneRecordForTheEarlierRule() {
        var plan = PLANNER.plan(
                sessionWithBid(201, "120.00", 4),
                List.of(proxy(11, 201, "300.00", 1), proxy(12, 101, "300.00", 2)),
                proxyChange(AuctionBidCommandType.UPSERT_PROXY, 101)
        );

        assertThat(plan.bids()).containsExactly(
                bid(201, BidSource.PROXY, "300.00", "120.00", 5)
        );
        assertThat(plan.leadingBidderId()).isEqualTo(201L);
    }

    @Test
    void loweringOrDisablingALeadingProxyDoesNotRetractAcceptedBid() {
        AuctionSession session = sessionWithBid(101, "400.00", 8);

        var lowered = PLANNER.plan(
                session,
                List.of(proxy(11, 101, "300.00", 2)),
                proxyChange(AuctionBidCommandType.UPSERT_PROXY, 101)
        );
        var disabled = PLANNER.plan(
                session,
                List.of(disabledProxy(11, 101, "500.00", 3)),
                proxyChange(AuctionBidCommandType.DISABLE_PROXY, 101)
        );

        assertThat(lowered.bids()).isEmpty();
        assertThat(lowered.displayPrice()).isEqualByComparingTo("400.00");
        assertThat(lowered.leadingProxyBidId()).isNull();
        assertThat(disabled.bids()).isEmpty();
        assertThat(disabled.leadingBidderId()).isEqualTo(101L);
    }

    @Test
    void rejectsLowManualBidAndInvalidCommandShapes() {
        assertThatThrownBy(() -> PLANNER.plan(
                sessionWithBid(201, "120.00", 1), List.of(), manual(101, "129.99")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum next bid");
        assertThatThrownBy(() -> new AuctionBidCommandPlanner.Command(
                AuctionBidCommandType.UPSERT_PROXY, 101, new BigDecimal("500.00")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain amount");
    }

    private static AuctionBidCommandPlanner.Command manual(long bidderId, String amount) {
        return AuctionBidCommandPlanner.Command.manualBid(bidderId, new BigDecimal(amount));
    }

    private static AuctionBidCommandPlanner.Command proxyChange(AuctionBidCommandType type, long bidderId) {
        return AuctionBidCommandPlanner.Command.proxyRuleChanged(type, bidderId);
    }

    private static AuctionBidCommandPlanner.PlannedBid bid(
            long bidderId,
            BidSource source,
            String amount,
            String previousPrice,
            long sequenceNo
    ) {
        return new AuctionBidCommandPlanner.PlannedBid(
                bidderId,
                source,
                new BigDecimal(amount),
                previousPrice == null ? null : new BigDecimal(previousPrice),
                sequenceNo
        );
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

    private static AuctionProxyBid proxy(long id, long bidderId, String maximum, long priority) {
        return new AuctionProxyBid(
                id, 1L, bidderId, new BigDecimal(maximum), AuctionProxyBidStatus.ACTIVE,
                priority, 0L, null, NOW, NOW
        );
    }

    private static AuctionProxyBid disabledProxy(long id, long bidderId, String maximum, long priority) {
        return new AuctionProxyBid(
                id, 1L, bidderId, new BigDecimal(maximum), AuctionProxyBidStatus.DISABLED,
                priority, 1L, NOW, NOW.minusSeconds(60), NOW
        );
    }
}

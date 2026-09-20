package io.github.carpl2.tidebid.auction.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionProxyBidLifecycleTest {

    private static final Instant CREATED = Instant.parse("2026-09-20T05:00:00Z");
    private static final Instant UPDATED = CREATED.plusSeconds(10);
    private static final AuctionProxyBidLifecycle LIFECYCLE = new AuctionProxyBidLifecycle();

    @Test
    void createsAnActiveRuleWithItsFirstPriority() {
        AuctionProxyBid created = LIFECYCLE.create(openSession(), 11, 101,
                new BigDecimal("500.00"), 7, CREATED);

        assertThat(created.auctionId()).isEqualTo(1L);
        assertThat(created.bidderId()).isEqualTo(101L);
        assertThat(created.status()).isEqualTo(AuctionProxyBidStatus.ACTIVE);
        assertThat(created.priority()).isEqualTo(7L);
        assertThat(created.version()).isZero();
        assertThat(created.disabledAt()).isNull();
    }

    @Test
    void updatingCanRaiseOrLowerMaximumButMustUseANewerPriority() {
        AuctionProxyBid existing = active(11, 101, "500.00", 7, 0);

        AuctionProxyBid lowered = LIFECYCLE.update(openSession(), existing,
                new BigDecimal("300.00"), 8, UPDATED);

        assertThat(lowered.maxAmount()).isEqualByComparingTo("300.00");
        assertThat(lowered.priority()).isEqualTo(8L);
        assertThat(lowered.version()).isEqualTo(1L);
        assertThat(lowered.status()).isEqualTo(AuctionProxyBidStatus.ACTIVE);
        assertThatThrownBy(() -> LIFECYCLE.update(openSession(), existing,
                new BigDecimal("600.00"), 7, UPDATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newer priority");
    }

    @Test
    void disablingIsIdempotentAndDoesNotChangeAnAlreadyDisabledRule() {
        AuctionProxyBid existing = active(11, 101, "500.00", 7, 2);
        AuctionProxyBid disabled = LIFECYCLE.disable(openSession(), existing, UPDATED);
        AuctionProxyBid repeated = LIFECYCLE.disable(openSession(), disabled, UPDATED.plusSeconds(1));

        assertThat(disabled.status()).isEqualTo(AuctionProxyBidStatus.DISABLED);
        assertThat(disabled.disabledAt()).isEqualTo(UPDATED);
        assertThat(disabled.version()).isEqualTo(3L);
        assertThat(repeated).isEqualTo(disabled);
    }

    @Test
    void aDisabledRuleCanBeUpsertedWithANewPriorityWithoutReusingTheOldVersion() {
        AuctionProxyBid disabled = new AuctionProxyBid(
                11L, 1L, 101L, new BigDecimal("500.00"), AuctionProxyBidStatus.DISABLED,
                7L, 2L, UPDATED, CREATED, UPDATED
        );

        AuctionProxyBid reactivated = LIFECYCLE.update(openSession(), disabled,
                new BigDecimal("600.00"), 9, UPDATED.plusSeconds(1));

        assertThat(reactivated.status()).isEqualTo(AuctionProxyBidStatus.ACTIVE);
        assertThat(reactivated.disabledAt()).isNull();
        assertThat(reactivated.priority()).isEqualTo(9L);
        assertThat(reactivated.version()).isEqualTo(3L);
    }

    @Test
    void lifecycleRejectsClosedAuctionsForeignRulesAndBackwardsTime() {
        AuctionSession closed = new AuctionSession(
                1L, 2L, 3L, new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("50.00"), null, null, 0,
                CREATED.minusSeconds(10), CREATED.plusSeconds(100), AuctionSessionStatus.CLOSED_UNSOLD,
                null, null, null, CREATED, 1L, CREATED.minusSeconds(100), UPDATED
        );
        AuctionProxyBid existing = active(11, 101, "500.00", 7, 0);

        assertThatThrownBy(() -> LIFECYCLE.create(closed, 12, 101,
                new BigDecimal("500.00"), 8, UPDATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("open auction");
        assertThatThrownBy(() -> LIFECYCLE.update(openSession(),
                foreignActive(12, 102, "500.00", 7), new BigDecimal("600.00"), 8, UPDATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another auction");
        assertThatThrownBy(() -> LIFECYCLE.update(openSession(), existing,
                new BigDecimal("600.00"), 8, CREATED.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("updatedAt");
    }

    @Test
    void effectiveMeansActiveRuleInAnOpenMatchingAuctionOnly() {
        AuctionProxyBid active = active(11, 101, "500.00", 7, 0);
        assertThat(LIFECYCLE.isEffective(openSession(), active)).isTrue();
        assertThat(LIFECYCLE.isEffective(openSession(), new AuctionProxyBid(
                11L, 1L, 101L, new BigDecimal("500.00"), AuctionProxyBidStatus.DISABLED,
                7L, 1L, UPDATED, CREATED, UPDATED
        ))).isFalse();
    }

    private static AuctionSession openSession() {
        return new AuctionSession(
                1L, 2L, 3L, new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("50.00"), null, null, 0,
                CREATED.minusSeconds(60), CREATED.plusSeconds(3600), AuctionSessionStatus.OPEN,
                0L, CREATED.minusSeconds(120), CREATED
        );
    }

    private static AuctionProxyBid active(long id, long bidderId, String max, long priority, long version) {
        return new AuctionProxyBid(
                id, 1L, bidderId, new BigDecimal(max), AuctionProxyBidStatus.ACTIVE,
                priority, version, null, CREATED, CREATED
        );
    }

    private static AuctionProxyBid foreignActive(long id, long bidderId, String max, long priority) {
        return new AuctionProxyBid(
                id, 2L, bidderId, new BigDecimal(max), AuctionProxyBidStatus.ACTIVE,
                priority, 0L, null, CREATED, CREATED
        );
    }
}

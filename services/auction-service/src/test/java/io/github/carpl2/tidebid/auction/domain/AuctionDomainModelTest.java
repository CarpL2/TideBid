package io.github.carpl2.tidebid.auction.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionDomainModelTest {

    private static final Instant NOW = Instant.parse("2026-09-12T00:00:00Z");

    @Test
    void permitsOnlySpecifiedStateTransitions() {
        assertThat(AuctionItemReviewStatus.DRAFT.canTransitionTo(AuctionItemReviewStatus.PENDING_REVIEW)).isTrue();
        assertThat(AuctionItemReviewStatus.REJECTED.canTransitionTo(AuctionItemReviewStatus.PENDING_REVIEW)).isTrue();
        assertThat(AuctionItemReviewStatus.PENDING_REVIEW.canTransitionTo(AuctionItemReviewStatus.APPROVED)).isTrue();
        assertThat(AuctionItemReviewStatus.PENDING_REVIEW.canTransitionTo(AuctionItemReviewStatus.REJECTED)).isTrue();
        assertThat(AuctionItemReviewStatus.APPROVED.canTransitionTo(AuctionItemReviewStatus.DRAFT)).isFalse();

        assertThat(AuctionSessionStatus.DRAFT.canTransitionTo(AuctionSessionStatus.SCHEDULED)).isTrue();
        assertThat(AuctionSessionStatus.SCHEDULED.canTransitionTo(AuctionSessionStatus.OPEN)).isTrue();
        assertThat(AuctionSessionStatus.OPEN.canTransitionTo(AuctionSessionStatus.AWAITING_CLOSE)).isTrue();
        assertThat(AuctionSessionStatus.AWAITING_CLOSE.canTransitionTo(AuctionSessionStatus.OPEN)).isFalse();

        assertThat(AuctionRegistrationStatus.PENDING_HOLD.canTransitionTo(AuctionRegistrationStatus.REGISTERED)).isTrue();
        assertThat(AuctionRegistrationStatus.PENDING_HOLD.canTransitionTo(AuctionRegistrationStatus.FAILED)).isTrue();
        assertThat(AuctionRegistrationStatus.REGISTERED.canTransitionTo(AuctionRegistrationStatus.FAILED)).isFalse();
    }

    @Test
    void rejectsInconsistentReviewAndBidSnapshots() {
        assertThatThrownBy(() -> new AuctionItem(
                1L, 2L, "Camera", "A carefully maintained camera", "ELECTRONICS",
                AuctionItemCondition.GOOD, AuctionItemReviewStatus.APPROVED, 1, 0,
                NOW, null, NOW, NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("approvedAt");

        assertThatThrownBy(() -> new BidRecord(
                1L, 2L, 3L, "request_1234", new BigDecimal("99.00"),
                new BigDecimal("100.00"), 2L, NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("previousPrice");
    }

    @Test
    void rejectsRegistrationResultThatDoesNotMatchItsStatus() {
        assertThatThrownBy(() -> new AuctionRegistration(
                1L, "REGISTRATION:1", 2L, 3L, new BigDecimal("100.00"),
                AuctionRegistrationStatus.FAILED, null, 1, null, NOW,
                null, null, null, 0L, NOW, NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("failureCode");
    }
}

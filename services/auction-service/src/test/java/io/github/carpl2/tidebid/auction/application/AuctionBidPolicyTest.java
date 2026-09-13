package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionBidPolicyTest {

    private static final Instant START = Instant.parse("2026-09-14T02:00:00Z");
    private static final Instant END = Instant.parse("2026-09-14T03:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-14T02:30:00Z");

    @Test
    void acceptsStartPriceAsTheFirstBidAndNormalizesItToTwoDecimals() {
        AuctionBidPolicy.ValidatedBid bid = AuctionBidPolicy.validate(
                session(null, 0, AuctionSessionStatus.OPEN),
                registration(AuctionRegistrationStatus.REGISTERED),
                20L,
                new BigDecimal("100"),
                NOW
        );

        assertThat(bid.amount()).isEqualTo(new BigDecimal("100.00"));
        assertThat(bid.previousPrice()).isNull();
        assertThat(bid.minimumNextBid()).isEqualByComparingTo("100.00");
        assertThat(bid.sequenceNo()).isEqualTo(1L);
        assertThat(bid.expectedVersion()).isEqualTo(7L);
    }

    @Test
    void acceptsAnyHigherAmountAfterAnExistingBid() {
        AuctionBidPolicy.ValidatedBid bid = AuctionBidPolicy.validate(
                session(new BigDecimal("130.00"), 3, AuctionSessionStatus.OPEN),
                registration(AuctionRegistrationStatus.REGISTERED),
                20L,
                new BigDecimal("167.5"),
                NOW
        );

        assertThat(bid.amount()).isEqualTo(new BigDecimal("167.50"));
        assertThat(bid.previousPrice()).isEqualByComparingTo("130.00");
        assertThat(bid.minimumNextBid()).isEqualByComparingTo("140.00");
        assertThat(bid.sequenceNo()).isEqualTo(4L);
    }

    @Test
    void rejectsAmountsBelowTheCurrentMinimum() {
        assertBusinessError(
                () -> AuctionBidPolicy.validate(
                        session(new BigDecimal("130.00"), 3, AuctionSessionStatus.OPEN),
                        registration(AuctionRegistrationStatus.REGISTERED),
                        20L,
                        new BigDecimal("139.99"),
                        NOW
                ),
                AuctionErrorCode.BID_TOO_LOW
        );
    }

    @Test
    void rejectsInvalidMoneyWithoutRoundingIt() {
        for (BigDecimal amount : new BigDecimal[]{
                BigDecimal.ZERO,
                new BigDecimal("-1.00"),
                new BigDecimal("1.001"),
                new BigDecimal("100000000000000000.00")
        }) {
            assertBusinessError(() -> AuctionBidPolicy.normalizeAmount(amount), AuctionErrorCode.BID_AMOUNT_INVALID);
        }
        assertBusinessError(() -> AuctionBidPolicy.normalizeAmount(null), AuctionErrorCode.BID_AMOUNT_INVALID);
    }

    @Test
    void requiresACompletedRegistration() {
        AuctionSession session = session(null, 0, AuctionSessionStatus.OPEN);

        assertBusinessError(
                () -> AuctionBidPolicy.validate(session, null, 20L, new BigDecimal("100.00"), NOW),
                AuctionErrorCode.REGISTRATION_REQUIRED
        );
        assertBusinessError(
                () -> AuctionBidPolicy.validate(
                        session, registration(AuctionRegistrationStatus.PENDING_HOLD),
                        20L, new BigDecimal("100.00"), NOW
                ),
                AuctionErrorCode.REGISTRATION_PENDING
        );
        assertBusinessError(
                () -> AuctionBidPolicy.validate(
                        session, registration(AuctionRegistrationStatus.FAILED),
                        20L, new BigDecimal("100.00"), NOW
                ),
                AuctionErrorCode.REGISTRATION_REQUIRED
        );
    }

    @Test
    void preventsTheSellerFromBidding() {
        assertBusinessError(
                () -> AuctionBidPolicy.validate(
                        session(null, 0, AuctionSessionStatus.OPEN),
                        registration(AuctionRegistrationStatus.REGISTERED),
                        10L,
                        new BigDecimal("100.00"),
                        NOW
                ),
                AuctionErrorCode.SELLER_CANNOT_PARTICIPATE
        );
    }

    @Test
    void rejectsBeforeStartAtAndExactlyAtEndAt() {
        AuctionRegistration registration = registration(AuctionRegistrationStatus.REGISTERED);

        assertBusinessError(
                () -> AuctionBidPolicy.validate(
                        session(null, 0, AuctionSessionStatus.SCHEDULED), registration,
                        20L, new BigDecimal("100.00"), START.minusNanos(1)
                ),
                AuctionErrorCode.AUCTION_NOT_STARTED
        );
        assertBusinessError(
                () -> AuctionBidPolicy.validate(
                        session(null, 0, AuctionSessionStatus.OPEN), registration,
                        20L, new BigDecimal("100.00"), END
                ),
                AuctionErrorCode.AUCTION_ENDED
        );
    }

    @Test
    void rejectsAStateThatDoesNotMatchTheCurrentTime() {
        assertBusinessError(
                () -> AuctionBidPolicy.validate(
                        session(null, 0, AuctionSessionStatus.SCHEDULED),
                        registration(AuctionRegistrationStatus.REGISTERED),
                        20L,
                        new BigDecimal("100.00"),
                        NOW
                ),
                AuctionErrorCode.AUCTION_STATE_CONFLICT
        );
    }

    @Test
    void treatsMismatchedRegistrationAsAnInternalConsistencyFailure() {
        AuctionRegistration wrongBidder = new AuctionRegistration(
                30L, "REGISTRATION:30", 1L, 21L, new BigDecimal("50.00"),
                AuctionRegistrationStatus.REGISTERED, null, 1, null, NOW.minusSeconds(20),
                null, null, NOW.minusSeconds(20), 1L, NOW.minusSeconds(30), NOW.minusSeconds(20)
        );

        assertThatThrownBy(() -> AuctionBidPolicy.validate(
                session(null, 0, AuctionSessionStatus.OPEN), wrongBidder,
                20L, new BigDecimal("100.00"), NOW
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("registration");
    }

    private static AuctionSession session(
            BigDecimal currentPrice,
            long bidCount,
            AuctionSessionStatus status
    ) {
        return new AuctionSession(
                1L, 2L, 10L, new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("50.00"), currentPrice, bidCount == 0 ? null : 99L, bidCount,
                START, END, status, 7L, START.minusSeconds(60), NOW
        );
    }

    private static AuctionRegistration registration(AuctionRegistrationStatus status) {
        String failureCode = status == AuctionRegistrationStatus.FAILED ? "ACCOUNT_BALANCE_INSUFFICIENT" : null;
        Instant registeredAt = status == AuctionRegistrationStatus.REGISTERED ? START.minusSeconds(60) : null;
        return new AuctionRegistration(
                30L, "REGISTRATION:30", 1L, 20L, new BigDecimal("50.00"), status,
                failureCode, 1, null, NOW.minusSeconds(20), null, null, registeredAt,
                1L, START.minusSeconds(120), NOW.minusSeconds(20)
        );
    }

    private static void assertBusinessError(Runnable invocation, AuctionErrorCode errorCode) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(errorCode)
                );
    }
}

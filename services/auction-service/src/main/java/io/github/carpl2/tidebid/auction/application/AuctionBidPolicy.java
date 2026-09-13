package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.core.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

public final class AuctionBidPolicy {

    private static final BigDecimal MAXIMUM_AMOUNT = new BigDecimal("99999999999999999.99");

    private AuctionBidPolicy() {
    }

    public static ValidatedBid validate(
            AuctionSession session,
            AuctionRegistration registration,
            long bidderId,
            BigDecimal amount,
            Instant now
    ) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (bidderId <= 0) {
            throw new IllegalArgumentException("bidderId must be positive");
        }
        BigDecimal normalizedAmount = normalizeAmount(amount);
        if (session.sellerId() == bidderId) {
            throw new BusinessException(AuctionErrorCode.SELLER_CANNOT_PARTICIPATE);
        }
        requireRegistered(session, registration, bidderId);
        requireOpenAt(session, now);

        BigDecimal minimumNextBid = session.minimumNextBid();
        if (normalizedAmount.compareTo(minimumNextBid) < 0) {
            throw new BusinessException(
                    AuctionErrorCode.BID_TOO_LOW,
                    "Bid amount must be at least " + minimumNextBid.toPlainString()
            );
        }
        long sequenceNo;
        try {
            sequenceNo = Math.addExact(session.bidCount(), 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("auction bid count overflow", exception);
        }
        return new ValidatedBid(
                normalizedAmount,
                session.currentPrice(),
                minimumNextBid,
                sequenceNo,
                session.version()
        );
    }

    public static BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null
                || amount.signum() <= 0
                || amount.scale() > 2
                || amount.compareTo(MAXIMUM_AMOUNT) > 0) {
            throw new BusinessException(AuctionErrorCode.BID_AMOUNT_INVALID);
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static void requireRegistered(
            AuctionSession session,
            AuctionRegistration registration,
            long bidderId
    ) {
        if (registration == null) {
            throw new BusinessException(AuctionErrorCode.REGISTRATION_REQUIRED);
        }
        if (registration.auctionId() != session.id() || registration.bidderId() != bidderId) {
            throw new IllegalStateException("registration does not belong to the auction and bidder");
        }
        if (registration.status() == AuctionRegistrationStatus.PENDING_HOLD) {
            throw new BusinessException(AuctionErrorCode.REGISTRATION_PENDING);
        }
        if (registration.status() != AuctionRegistrationStatus.REGISTERED) {
            throw new BusinessException(AuctionErrorCode.REGISTRATION_REQUIRED);
        }
    }

    private static void requireOpenAt(AuctionSession session, Instant now) {
        if (now.isBefore(session.startAt())) {
            throw new BusinessException(AuctionErrorCode.AUCTION_NOT_STARTED);
        }
        if (!now.isBefore(session.endAt())) {
            throw new BusinessException(AuctionErrorCode.AUCTION_ENDED);
        }
        if (session.status() != AuctionSessionStatus.OPEN) {
            throw new BusinessException(AuctionErrorCode.AUCTION_STATE_CONFLICT);
        }
    }

    public record ValidatedBid(
            BigDecimal amount,
            BigDecimal previousPrice,
            BigDecimal minimumNextBid,
            long sequenceNo,
            long expectedVersion
    ) {
    }
}

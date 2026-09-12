package io.github.carpl2.tidebid.auction.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record AuctionRegistration(
        long id,
        String registrationNo,
        long auctionId,
        long bidderId,
        BigDecimal depositAmount,
        AuctionRegistrationStatus status,
        String failureCode,
        int attemptCount,
        Instant nextRetryAt,
        Instant lastAttemptAt,
        String leaseOwner,
        Instant leaseUntil,
        Instant registeredAt,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public AuctionRegistration {
        AuctionDomainRules.positiveId(id, "id");
        registrationNo = AuctionDomainRules.businessKey(registrationNo, "registrationNo");
        AuctionDomainRules.positiveId(auctionId, "auctionId");
        AuctionDomainRules.positiveId(bidderId, "bidderId");
        depositAmount = AuctionDomainRules.positiveAmount(depositAmount, "depositAmount");
        Objects.requireNonNull(status, "status must not be null");
        failureCode = AuctionDomainRules.optionalText(failureCode, 64, "failureCode");
        AuctionDomainRules.nonNegative(attemptCount, "attemptCount");
        leaseOwner = AuctionDomainRules.optionalText(leaseOwner, 64, "leaseOwner");
        if ((leaseOwner == null) != (leaseUntil == null)) {
            throw new IllegalArgumentException("leaseOwner and leaseUntil must both be present or absent");
        }
        if (status == AuctionRegistrationStatus.PENDING_HOLD
                && (failureCode != null || registeredAt != null)) {
            throw new IllegalArgumentException("pending registration cannot contain a final result");
        }
        if (status == AuctionRegistrationStatus.REGISTERED
                && (failureCode != null || registeredAt == null)) {
            throw new IllegalArgumentException("registered state requires registeredAt and no failureCode");
        }
        if (status == AuctionRegistrationStatus.FAILED
                && (failureCode == null || registeredAt != null)) {
            throw new IllegalArgumentException("failed state requires failureCode and no registeredAt");
        }
        AuctionDomainRules.nonNegative(version, "version");
        createdAt = AuctionDomainRules.instant(createdAt, "createdAt");
        updatedAt = AuctionDomainRules.instant(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}

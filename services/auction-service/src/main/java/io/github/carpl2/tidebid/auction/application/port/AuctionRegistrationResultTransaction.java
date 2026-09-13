package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;

import java.time.Instant;

public interface AuctionRegistrationResultTransaction {

    AuctionRegistration markRegistered(long registrationId, Instant attemptedAt);

    AuctionRegistration markFailed(long registrationId, String failureCode, Instant attemptedAt);

    AuctionRegistration scheduleRetry(long registrationId, Instant attemptedAt, Instant nextRetryAt);

    AuctionRegistration markRecoveryExhausted(long registrationId, Instant attemptedAt);
}

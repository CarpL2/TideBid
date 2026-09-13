package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationResultTransaction;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionRegistrationMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
@Profile({"local-db", "nacos"})
public class MybatisAuctionRegistrationResultTransaction implements AuctionRegistrationResultTransaction {

    private final AuctionRegistrationMapper registrationMapper;
    private final AuctionRegistrationRepository registrationRepository;

    public MybatisAuctionRegistrationResultTransaction(
            AuctionRegistrationMapper registrationMapper,
            AuctionRegistrationRepository registrationRepository
    ) {
        this.registrationMapper = registrationMapper;
        this.registrationRepository = registrationRepository;
    }

    @Override
    @Transactional
    public AuctionRegistration markRegistered(long registrationId, Instant attemptedAt) {
        requireArguments(registrationId, attemptedAt);
        registrationMapper.markRegistered(registrationId, attemptedAt);
        return reload(registrationId);
    }

    @Override
    @Transactional
    public AuctionRegistration markFailed(long registrationId, String failureCode, Instant attemptedAt) {
        requireArguments(registrationId, attemptedAt);
        String normalizedFailureCode = failureCode == null ? "" : failureCode.trim();
        if (normalizedFailureCode.isEmpty() || normalizedFailureCode.length() > 64) {
            throw new IllegalArgumentException("failureCode must contain 1 to 64 characters");
        }
        registrationMapper.markFailed(registrationId, normalizedFailureCode, attemptedAt);
        return reload(registrationId);
    }

    @Override
    @Transactional
    public AuctionRegistration scheduleRetry(long registrationId, Instant attemptedAt, Instant nextRetryAt) {
        requireArguments(registrationId, attemptedAt);
        if (nextRetryAt == null || !nextRetryAt.isAfter(attemptedAt)) {
            throw new IllegalArgumentException("nextRetryAt must be after attemptedAt");
        }
        registrationMapper.scheduleRetry(registrationId, attemptedAt, nextRetryAt);
        return reload(registrationId);
    }

    private AuctionRegistration reload(long registrationId) {
        return registrationRepository.findById(registrationId)
                .orElseThrow(() -> new IllegalStateException("Registration result update target was not found"));
    }

    private static void requireArguments(long registrationId, Instant attemptedAt) {
        if (registrationId <= 0 || attemptedAt == null) {
            throw new IllegalArgumentException("registrationId and attemptedAt are invalid");
        }
    }
}

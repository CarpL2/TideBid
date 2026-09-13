package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationCreationTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Component
@Profile({"local-db", "nacos"})
public class MybatisAuctionRegistrationCreationTransaction implements AuctionRegistrationCreationTransaction {

    private final AuctionRegistrationRepository registrationRepository;

    public MybatisAuctionRegistrationCreationTransaction(AuctionRegistrationRepository registrationRepository) {
        this.registrationRepository = registrationRepository;
    }

    @Override
    @Transactional
    public AuctionRegistration createPending(AuctionRegistration registration) {
        Objects.requireNonNull(registration, "registration must not be null");
        if (registration.status() != AuctionRegistrationStatus.PENDING_HOLD
                || registration.attemptCount() != 0
                || registration.nextRetryAt() != null
                || registration.lastAttemptAt() != null
                || registration.leaseOwner() != null
                || registration.leaseUntil() != null) {
            throw new IllegalArgumentException("registration must be a new pending hold");
        }
        try {
            return registrationRepository.insert(registration);
        } catch (DuplicateKeyException exception) {
            throw new DuplicateRegistrationException(exception);
        }
    }
}

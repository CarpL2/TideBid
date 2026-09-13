package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRecoveryTransaction;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionRegistrationMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
@Profile({"local-db", "nacos"})
public class MybatisAuctionRegistrationRecoveryTransaction implements AuctionRegistrationRecoveryTransaction {

    private final AuctionRegistrationMapper registrationMapper;

    public MybatisAuctionRegistrationRecoveryTransaction(AuctionRegistrationMapper registrationMapper) {
        this.registrationMapper = registrationMapper;
    }

    @Override
    @Transactional
    public List<AuctionRegistration> claimDue(
            Instant now,
            String leaseOwner,
            Instant leaseUntil,
            int batchSize
    ) {
        if (now == null || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("leaseUntil must be after now");
        }
        String normalizedOwner = MybatisAuctionItemRepository.requireText(leaseOwner, 64, "leaseOwner");
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }

        List<AuctionRegistration> claimed = new ArrayList<>();
        for (Long registrationId : registrationMapper.findDueRecoveryIds(now, batchSize)) {
            int updated = registrationMapper.claimForRecovery(
                    registrationId, now, normalizedOwner, leaseUntil
            );
            if (updated == 1) {
                var entity = registrationMapper.selectById(registrationId);
                if (entity == null) {
                    throw new IllegalStateException("Claimed registration could not be reloaded");
                }
                AuctionRegistration registration = AuctionPersistenceMapping.toDomain(entity);
                claimed.add(registration);
            }
        }
        return List.copyOf(claimed);
    }
}

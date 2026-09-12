package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionRegistrationEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionRegistrationMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@Profile({"local-db", "nacos"})
public class MybatisAuctionRegistrationRepository implements AuctionRegistrationRepository {

    private final AuctionRegistrationMapper registrationMapper;

    public MybatisAuctionRegistrationRepository(AuctionRegistrationMapper registrationMapper) {
        this.registrationMapper = registrationMapper;
    }

    @Override
    public AuctionRegistration insert(AuctionRegistration registration) {
        AuctionRegistrationEntity entity = AuctionPersistenceMapping.toEntity(registration);
        MybatisAuctionItemRepository.requireSingleRow(registrationMapper.insert(entity), "registration insert");
        return findById(entity.getId()).orElseThrow(
                () -> new IllegalStateException("Inserted registration could not be reloaded")
        );
    }

    @Override
    public Optional<AuctionRegistration> findById(long registrationId) {
        MybatisAuctionItemRepository.requirePositive(registrationId, "registrationId");
        return Optional.ofNullable(registrationMapper.selectById(registrationId))
                .map(AuctionPersistenceMapping::toDomain);
    }

    @Override
    public Optional<AuctionRegistration> findByRegistrationNo(String registrationNo) {
        String normalized = MybatisAuctionItemRepository.requireText(registrationNo, 64, "registrationNo");
        return Optional.ofNullable(registrationMapper.selectOne(new LambdaQueryWrapper<AuctionRegistrationEntity>()
                        .eq(AuctionRegistrationEntity::getRegistrationNo, normalized)))
                .map(AuctionPersistenceMapping::toDomain);
    }

    @Override
    public Optional<AuctionRegistration> findByAuctionAndBidder(long auctionId, long bidderId) {
        MybatisAuctionItemRepository.requirePositive(auctionId, "auctionId");
        MybatisAuctionItemRepository.requirePositive(bidderId, "bidderId");
        return Optional.ofNullable(registrationMapper.selectOne(new LambdaQueryWrapper<AuctionRegistrationEntity>()
                        .eq(AuctionRegistrationEntity::getAuctionId, auctionId)
                        .eq(AuctionRegistrationEntity::getBidderId, bidderId)))
                .map(AuctionPersistenceMapping::toDomain);
    }
}

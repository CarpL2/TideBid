package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.carpl2.tidebid.auction.application.port.AuctionProxyBidRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBid;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBidStatus;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionProxyBidEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionProxyBidMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@Profile({"local-db", "nacos"})
public class MybatisAuctionProxyBidRepository implements AuctionProxyBidRepository {
    private final AuctionProxyBidMapper mapper;

    public MybatisAuctionProxyBidRepository(AuctionProxyBidMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AuctionProxyBid insert(AuctionProxyBid proxyBid) {
        AuctionProxyBidEntity entity = AuctionPersistenceMapping.toEntity(requireProxyBid(proxyBid));
        MybatisAuctionItemRepository.requireSingleRow(mapper.insert(entity), "auction proxy bid insert");
        return findByAuctionAndBidder(entity.getAuctionId(), entity.getBidderId())
                .orElseThrow(() -> new IllegalStateException("Inserted proxy bid could not be reloaded"));
    }

    @Override
    public boolean update(AuctionProxyBid proxyBid) {
        return mapper.updateById(AuctionPersistenceMapping.toEntity(requireProxyBid(proxyBid))) == 1;
    }

    @Override
    public Optional<AuctionProxyBid> findByAuctionAndBidder(long auctionId, long bidderId) {
        requirePositive(auctionId, "auctionId");
        requirePositive(bidderId, "bidderId");
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<AuctionProxyBidEntity>()
                        .eq(AuctionProxyBidEntity::getAuctionId, auctionId)
                        .eq(AuctionProxyBidEntity::getBidderId, bidderId)))
                .map(AuctionPersistenceMapping::toDomain);
    }

    @Override
    public List<AuctionProxyBid> findActiveByAuction(long auctionId) {
        requirePositive(auctionId, "auctionId");
        return mapper.selectList(new LambdaQueryWrapper<AuctionProxyBidEntity>()
                        .eq(AuctionProxyBidEntity::getAuctionId, auctionId)
                        .eq(AuctionProxyBidEntity::getStatus, AuctionProxyBidStatus.ACTIVE.name())
                        .orderByDesc(AuctionProxyBidEntity::getMaxAmount)
                        .orderByAsc(AuctionProxyBidEntity::getPriority)
                        .orderByAsc(AuctionProxyBidEntity::getId))
                .stream().map(AuctionPersistenceMapping::toDomain).toList();
    }

    private static AuctionProxyBid requireProxyBid(AuctionProxyBid proxyBid) {
        if (proxyBid == null) {
            throw new IllegalArgumentException("proxyBid must not be null");
        }
        return proxyBid;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

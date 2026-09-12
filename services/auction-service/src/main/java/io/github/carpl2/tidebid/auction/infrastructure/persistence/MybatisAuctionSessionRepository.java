package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionSessionEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.BidRecordEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionSessionMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.BidRecordMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@Profile({"local-db", "nacos"})
public class MybatisAuctionSessionRepository implements AuctionSessionRepository {

    private final AuctionSessionMapper sessionMapper;
    private final BidRecordMapper bidMapper;

    public MybatisAuctionSessionRepository(AuctionSessionMapper sessionMapper, BidRecordMapper bidMapper) {
        this.sessionMapper = sessionMapper;
        this.bidMapper = bidMapper;
    }

    @Override
    public AuctionSession insertSession(AuctionSession session) {
        AuctionSessionEntity entity = AuctionPersistenceMapping.toEntity(session);
        MybatisAuctionItemRepository.requireSingleRow(sessionMapper.insert(entity), "auction session insert");
        return findSessionById(entity.getId()).orElseThrow(
                () -> new IllegalStateException("Inserted auction session could not be reloaded")
        );
    }

    @Override
    public Optional<AuctionSession> findSessionById(long auctionId) {
        MybatisAuctionItemRepository.requirePositive(auctionId, "auctionId");
        return Optional.ofNullable(sessionMapper.selectById(auctionId)).map(AuctionPersistenceMapping::toDomain);
    }

    @Override
    public Optional<AuctionSession> findSessionByItemId(long itemId) {
        MybatisAuctionItemRepository.requirePositive(itemId, "itemId");
        return Optional.ofNullable(sessionMapper.selectOne(new LambdaQueryWrapper<AuctionSessionEntity>()
                        .eq(AuctionSessionEntity::getItemId, itemId)))
                .map(AuctionPersistenceMapping::toDomain);
    }

    @Override
    public boolean updateDraftSession(AuctionSession session) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        return sessionMapper.updateDraft(AuctionPersistenceMapping.toEntity(session)) == 1;
    }

    @Override
    public BidRecord insertBid(BidRecord bid) {
        BidRecordEntity entity = AuctionPersistenceMapping.toEntity(bid);
        MybatisAuctionItemRepository.requireSingleRow(bidMapper.insert(entity), "bid record insert");
        return Optional.ofNullable(bidMapper.selectById(entity.getId()))
                .map(AuctionPersistenceMapping::toDomain)
                .orElseThrow(() -> new IllegalStateException("Inserted bid record could not be reloaded"));
    }

    @Override
    public Optional<BidRecord> findBid(long bidderId, String requestId) {
        MybatisAuctionItemRepository.requirePositive(bidderId, "bidderId");
        String normalizedRequestId = MybatisAuctionItemRepository.requireText(requestId, 48, "requestId");
        return Optional.ofNullable(bidMapper.selectOne(new LambdaQueryWrapper<BidRecordEntity>()
                        .eq(BidRecordEntity::getBidderId, bidderId)
                        .eq(BidRecordEntity::getRequestId, normalizedRequestId)))
                .map(AuctionPersistenceMapping::toDomain);
    }
}

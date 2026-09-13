package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionSessionEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.BidRecordEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionSessionMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.BidRecordMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
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
    public List<AuctionSession> findSessionsByItemIds(List<Long> itemIds) {
        List<Long> normalizedIds = MybatisAuctionItemRepository.requireIds(itemIds, "itemIds");
        if (normalizedIds.isEmpty()) {
            return List.of();
        }
        return sessionMapper.selectList(new LambdaQueryWrapper<AuctionSessionEntity>()
                        .in(AuctionSessionEntity::getItemId, normalizedIds)
                        .orderByAsc(AuctionSessionEntity::getItemId))
                .stream()
                .map(AuctionPersistenceMapping::toDomain)
                .toList();
    }

    @Override
    public List<AuctionSession> findDueScheduledSessions(Instant dueAt, int limit) {
        if (dueAt == null) {
            throw new IllegalArgumentException("dueAt must not be null");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return sessionMapper.selectList(new LambdaQueryWrapper<AuctionSessionEntity>()
                        .eq(AuctionSessionEntity::getStatus, AuctionSessionStatus.SCHEDULED.name())
                        .le(AuctionSessionEntity::getStartAt, dueAt)
                        .orderByAsc(AuctionSessionEntity::getStartAt)
                        .orderByAsc(AuctionSessionEntity::getId)
                        .last("LIMIT " + limit))
                .stream()
                .map(AuctionPersistenceMapping::toDomain)
                .toList();
    }

    @Override
    public boolean updateDraftSession(AuctionSession session) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        return sessionMapper.updateDraft(AuctionPersistenceMapping.toEntity(session)) == 1;
    }

    @Override
    public boolean scheduleDraftSession(
            long auctionId,
            long itemId,
            long sellerId,
            long expectedVersion,
            Instant scheduledAt
    ) {
        MybatisAuctionItemRepository.requirePositive(auctionId, "auctionId");
        MybatisAuctionItemRepository.requirePositive(itemId, "itemId");
        MybatisAuctionItemRepository.requirePositive(sellerId, "sellerId");
        if (expectedVersion < 0 || scheduledAt == null) {
            throw new IllegalArgumentException("expectedVersion and scheduledAt are invalid");
        }
        return sessionMapper.scheduleDraft(
                auctionId, itemId, sellerId, expectedVersion, scheduledAt
        ) == 1;
    }

    @Override
    public boolean openScheduledSession(long auctionId, long expectedVersion, Instant openedAt) {
        MybatisAuctionItemRepository.requirePositive(auctionId, "auctionId");
        if (expectedVersion < 0 || openedAt == null) {
            throw new IllegalArgumentException("expectedVersion and openedAt are invalid");
        }
        return sessionMapper.openScheduled(auctionId, expectedVersion, openedAt) == 1;
    }

    @Override
    public boolean markOpenSessionAwaitingClose(long auctionId, long expectedVersion, Instant endedAt) {
        MybatisAuctionItemRepository.requirePositive(auctionId, "auctionId");
        if (expectedVersion < 0 || endedAt == null) {
            throw new IllegalArgumentException("expectedVersion and endedAt are invalid");
        }
        return sessionMapper.markAwaitingClose(auctionId, expectedVersion, endedAt) == 1;
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

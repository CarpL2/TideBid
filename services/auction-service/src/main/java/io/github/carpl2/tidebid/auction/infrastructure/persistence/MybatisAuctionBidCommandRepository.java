package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.carpl2.tidebid.auction.application.port.AuctionBidCommandRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommand;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionBidCommandEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionBidCommandMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@Profile({"local-db", "nacos"})
public class MybatisAuctionBidCommandRepository implements AuctionBidCommandRepository {
    private final AuctionBidCommandMapper mapper;

    public MybatisAuctionBidCommandRepository(AuctionBidCommandMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AuctionBidCommand insert(AuctionBidCommand command) {
        AuctionBidCommandEntity entity = AuctionPersistenceMapping.toEntity(requireCommand(command));
        MybatisAuctionItemRepository.requireSingleRow(mapper.insert(entity), "auction bid command insert");
        return findByActorAndRequest(entity.getActorId(), entity.getRequestId())
                .orElseThrow(() -> new IllegalStateException("Inserted bid command could not be reloaded"));
    }

    @Override
    public boolean update(AuctionBidCommand command) {
        AuctionBidCommand normalized = requireCommand(command);
        return mapper.updateById(AuctionPersistenceMapping.toEntity(normalized)) == 1;
    }

    @Override
    public Optional<AuctionBidCommand> findByActorAndRequest(long actorId, String requestId) {
        if (actorId <= 0) {
            throw new IllegalArgumentException("actorId must be positive");
        }
        String normalizedRequestId = MybatisAuctionItemRepository.requireText(requestId, 48, "requestId");
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<AuctionBidCommandEntity>()
                        .eq(AuctionBidCommandEntity::getActorId, actorId)
                        .eq(AuctionBidCommandEntity::getRequestId, normalizedRequestId)))
                .map(AuctionPersistenceMapping::toDomain);
    }

    private static AuctionBidCommand requireCommand(AuctionBidCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return command;
    }
}

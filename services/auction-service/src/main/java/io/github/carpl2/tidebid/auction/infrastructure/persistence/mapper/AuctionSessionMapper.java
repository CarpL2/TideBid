package io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface AuctionSessionMapper extends BaseMapper<AuctionSessionEntity> {

    @Update("""
            UPDATE auction_session
            SET start_price = #{session.startPrice},
                bid_increment = #{session.bidIncrement},
                deposit_amount = #{session.depositAmount},
                start_at = #{session.startAt},
                end_at = #{session.endAt},
                updated_at = #{session.updatedAt},
                version = version + 1
            WHERE id = #{session.id}
              AND item_id = #{session.itemId}
              AND seller_id = #{session.sellerId}
              AND status = 'DRAFT'
              AND version = #{session.version}
            """)
    int updateDraft(@Param("session") AuctionSessionEntity session);

    @Update("""
            UPDATE auction_session
            SET status = 'SCHEDULED',
                updated_at = #{scheduledAt},
                version = version + 1
            WHERE id = #{auctionId}
              AND item_id = #{itemId}
              AND seller_id = #{sellerId}
              AND status = 'DRAFT'
              AND version = #{expectedVersion}
              AND start_at > #{scheduledAt}
            """)
    int scheduleDraft(
            @Param("auctionId") long auctionId,
            @Param("itemId") long itemId,
            @Param("sellerId") long sellerId,
            @Param("expectedVersion") long expectedVersion,
            @Param("scheduledAt") Instant scheduledAt
    );

    @Update("""
            UPDATE auction_session
            SET status = 'OPEN',
                updated_at = #{openedAt},
                version = version + 1
            WHERE id = #{auctionId}
              AND status = 'SCHEDULED'
              AND version = #{expectedVersion}
              AND start_at <= #{openedAt}
            """)
    int openScheduled(
            @Param("auctionId") long auctionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("openedAt") Instant openedAt
    );

    @Update("""
            UPDATE auction_session
            SET status = 'AWAITING_CLOSE',
                updated_at = #{endedAt},
                version = version + 1
            WHERE id = #{auctionId}
              AND status = 'OPEN'
              AND version = #{expectedVersion}
              AND end_at <= #{endedAt}
            """)
    int markAwaitingClose(
            @Param("auctionId") long auctionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("endedAt") Instant endedAt
    );
}

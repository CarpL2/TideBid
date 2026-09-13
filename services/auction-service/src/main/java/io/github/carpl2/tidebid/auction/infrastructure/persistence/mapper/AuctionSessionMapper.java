package io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
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

    @Update("""
            UPDATE auction_session
            SET current_price = #{amount},
                current_bidder_id = #{bidderId},
                bid_count = bid_count + 1,
                updated_at = #{acceptedAt},
                version = version + 1
            WHERE id = #{auctionId}
              AND status = 'OPEN'
              AND version = #{expectedVersion}
              AND start_at <= #{acceptedAt}
              AND end_at > #{acceptedAt}
              AND ((bid_count = 0
                    AND current_price IS NULL
                    AND #{previousPrice} IS NULL
                    AND #{sequenceNo} = 1
                    AND #{amount} >= start_price)
                OR (bid_count > 0
                    AND current_price = #{previousPrice}
                    AND #{sequenceNo} = bid_count + 1
                    AND #{amount} >= current_price + bid_increment))
            """)
    int acceptBid(
            @Param("auctionId") long auctionId,
            @Param("bidderId") long bidderId,
            @Param("amount") BigDecimal amount,
            @Param("previousPrice") BigDecimal previousPrice,
            @Param("sequenceNo") long sequenceNo,
            @Param("expectedVersion") long expectedVersion,
            @Param("acceptedAt") Instant acceptedAt
    );
}

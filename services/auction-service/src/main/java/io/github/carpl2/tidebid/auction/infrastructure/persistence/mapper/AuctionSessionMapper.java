package io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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
}

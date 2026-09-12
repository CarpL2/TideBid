package io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionItemEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AuctionItemMapper extends BaseMapper<AuctionItemEntity> {

    @Update("""
            UPDATE auction_item
            SET title = #{item.title},
                description = #{item.description},
                category = #{item.category},
                item_condition = #{item.itemCondition},
                updated_at = #{item.updatedAt},
                version = version + 1
            WHERE id = #{item.id}
              AND seller_id = #{item.sellerId}
              AND review_status IN ('DRAFT', 'REJECTED')
              AND version = #{item.version}
            """)
    int updateEditable(@Param("item") AuctionItemEntity item);
}

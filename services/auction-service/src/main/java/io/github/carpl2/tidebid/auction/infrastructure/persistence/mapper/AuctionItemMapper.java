package io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionItemEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AuctionItemMapper extends BaseMapper<AuctionItemEntity> {

    @Select("""
            SELECT id, seller_id, title, description, category, item_condition,
                   review_status, submission_version, version, submitted_at, approved_at,
                   created_at, updated_at
            FROM auction_item
            WHERE seller_id = #{sellerId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<AuctionItemEntity> selectSellerPage(
            @Param("sellerId") long sellerId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("SELECT COUNT(*) FROM auction_item WHERE seller_id = #{sellerId}")
    long countBySeller(@Param("sellerId") long sellerId);

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

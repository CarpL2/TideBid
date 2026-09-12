package io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionItemImageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface AuctionItemImageMapper extends BaseMapper<AuctionItemImageEntity> {

    @Update("""
            UPDATE auction_item_image
            SET item_id = #{itemId},
                sort_order = #{sortOrder},
                storage_status = 'BOUND',
                updated_at = #{boundAt}
            WHERE id = #{imageId}
              AND owner_id = #{ownerId}
              AND storage_status = 'PENDING'
              AND item_id IS NULL
              AND sort_order IS NULL
              AND upload_expires_at > #{boundAt}
            """)
    int bindPending(
            @Param("imageId") long imageId,
            @Param("ownerId") long ownerId,
            @Param("itemId") long itemId,
            @Param("sortOrder") int sortOrder,
            @Param("boundAt") Instant boundAt
    );
}

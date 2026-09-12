package io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionItemImageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface AuctionItemImageMapper extends BaseMapper<AuctionItemImageEntity> {

    @Select("""
            SELECT id, item_id, owner_id, object_key, original_filename, content_type,
                   content_length, content_sha256, sort_order, storage_status,
                   upload_expires_at, created_at, updated_at
            FROM auction_item_image
            WHERE storage_status = 'PENDING'
              AND item_id IS NULL
              AND sort_order IS NULL
              AND upload_expires_at <= #{uploadExpiredAt}
              AND created_at <= #{createdBefore}
            ORDER BY created_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<AuctionItemImageEntity> selectPendingCleanupCandidates(
            @Param("uploadExpiredAt") Instant uploadExpiredAt,
            @Param("createdBefore") Instant createdBefore,
            @Param("limit") int limit
    );

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

    @Update("""
            UPDATE auction_item_image
            SET storage_status = 'EXPIRED',
                updated_at = #{expiredAt}
            WHERE id = #{imageId}
              AND storage_status = 'PENDING'
              AND item_id IS NULL
              AND sort_order IS NULL
              AND upload_expires_at <= #{uploadExpiredAt}
              AND created_at <= #{createdBefore}
            """)
    int expirePending(
            @Param("imageId") long imageId,
            @Param("uploadExpiredAt") Instant uploadExpiredAt,
            @Param("createdBefore") Instant createdBefore,
            @Param("expiredAt") Instant expiredAt
    );
}

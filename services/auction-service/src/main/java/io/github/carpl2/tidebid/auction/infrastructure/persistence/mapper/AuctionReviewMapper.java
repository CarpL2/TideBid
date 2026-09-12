package io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionReviewEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuctionReviewMapper extends BaseMapper<AuctionReviewEntity> {
}

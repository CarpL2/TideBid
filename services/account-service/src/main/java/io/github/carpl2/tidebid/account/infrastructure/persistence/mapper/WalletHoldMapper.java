package io.github.carpl2.tidebid.account.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletHoldEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WalletHoldMapper extends BaseMapper<WalletHoldEntity> {

    @Select("""
            SELECT id, hold_no, user_id, business_type, amount, captured_amount, released_amount,
                   status, settlement_event_id, settled_at, version, created_at, updated_at
            FROM wallet_hold
            WHERE hold_no = #{holdNo}
            """)
    WalletHoldEntity selectByHoldNo(@Param("holdNo") String holdNo);
}

package io.github.carpl2.tidebid.account.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletAccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WalletAccountMapper extends BaseMapper<WalletAccountEntity> {

    @Select("""
            SELECT id, user_id, available_balance, frozen_balance, version, created_at, updated_at
            FROM wallet_account
            WHERE user_id = #{userId}
            """)
    WalletAccountEntity selectByUserId(@Param("userId") long userId);
}

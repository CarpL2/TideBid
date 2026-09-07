package io.github.carpl2.tidebid.account.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletAccountEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WalletAccountMapper extends BaseMapper<WalletAccountEntity> {
}

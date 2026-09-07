package io.github.carpl2.tidebid.account.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletLedgerEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WalletLedgerMapper extends BaseMapper<WalletLedgerEntity> {
}

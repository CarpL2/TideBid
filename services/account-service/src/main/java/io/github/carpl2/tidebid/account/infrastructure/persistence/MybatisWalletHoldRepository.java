package io.github.carpl2.tidebid.account.infrastructure.persistence;

import io.github.carpl2.tidebid.account.application.port.WalletHoldRepository;
import io.github.carpl2.tidebid.account.domain.WalletHold;
import io.github.carpl2.tidebid.account.domain.WalletHoldBusinessType;
import io.github.carpl2.tidebid.account.domain.WalletHoldStatus;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletHoldEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletHoldMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

@Repository
@Profile({"local-db", "nacos"})
public class MybatisWalletHoldRepository implements WalletHoldRepository {

    private final WalletHoldMapper walletHoldMapper;

    public MybatisWalletHoldRepository(WalletHoldMapper walletHoldMapper) {
        this.walletHoldMapper = walletHoldMapper;
    }

    @Override
    public Optional<WalletHold> findByHoldNo(String holdNo) {
        String normalizedHoldNo = WalletHold.requireHoldNo(holdNo);
        return Optional.ofNullable(walletHoldMapper.selectByHoldNo(normalizedHoldNo))
                .map(MybatisWalletHoldRepository::toDomain);
    }

    @Override
    public WalletHold insertHeld(
            String holdNo,
            long userId,
            WalletHoldBusinessType businessType,
            BigDecimal amount
    ) {
        String normalizedHoldNo = WalletHold.requireHoldNo(holdNo);
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        Objects.requireNonNull(businessType, "businessType must not be null");
        BigDecimal validatedAmount = WalletHold.requireAmount(amount);

        WalletHoldEntity entity = new WalletHoldEntity();
        entity.setHoldNo(normalizedHoldNo);
        entity.setUserId(userId);
        entity.setBusinessType(businessType.name());
        entity.setAmount(validatedAmount);
        entity.setStatus(WalletHoldStatus.HELD.name());
        if (walletHoldMapper.insert(entity) != 1) {
            throw new IllegalStateException("Expected one inserted row for wallet hold");
        }
        WalletHoldEntity stored = walletHoldMapper.selectByHoldNo(normalizedHoldNo);
        if (stored == null) {
            throw new IllegalStateException("Inserted wallet hold could not be reloaded");
        }
        return toDomain(stored);
    }

    private static WalletHold toDomain(WalletHoldEntity entity) {
        return new WalletHold(
                entity.getId(),
                entity.getHoldNo(),
                entity.getUserId(),
                WalletHoldBusinessType.valueOf(entity.getBusinessType()),
                entity.getAmount(),
                WalletHoldStatus.valueOf(entity.getStatus()),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}

package io.github.carpl2.tidebid.account.application.port;

import io.github.carpl2.tidebid.account.domain.WalletHold;
import io.github.carpl2.tidebid.account.domain.WalletHoldBusinessType;

import java.math.BigDecimal;
import java.util.Optional;

public interface WalletHoldRepository {

    Optional<WalletHold> findByHoldNo(String holdNo);

    WalletHold insertHeld(
            String holdNo,
            long userId,
            WalletHoldBusinessType businessType,
            BigDecimal amount
    );
}

package io.github.carpl2.tidebid.account.application;

import io.github.carpl2.tidebid.account.domain.WalletHoldBusinessType;

import java.math.BigDecimal;

public record HoldWalletFundsCommand(
        String holdNo,
        long userId,
        WalletHoldBusinessType businessType,
        BigDecimal amount
) {
}

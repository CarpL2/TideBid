package io.github.carpl2.tidebid.account.api;

import io.github.carpl2.tidebid.account.application.WalletBalance;

import java.math.BigDecimal;

public record CurrentWalletResponse(
        long userId,
        BigDecimal availableBalance,
        BigDecimal frozenBalance
) {
    static CurrentWalletResponse from(WalletBalance wallet) {
        return new CurrentWalletResponse(
                wallet.userId(),
                wallet.availableBalance(),
                wallet.frozenBalance()
        );
    }
}

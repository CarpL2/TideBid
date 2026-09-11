package io.github.carpl2.tidebid.account.api;

import io.github.carpl2.tidebid.account.application.WalletBalance;

import java.math.BigDecimal;

public record CurrentWalletResponse(
        String userId,
        BigDecimal availableBalance,
        BigDecimal frozenBalance
) {
    static CurrentWalletResponse from(WalletBalance wallet) {
        return new CurrentWalletResponse(
                Long.toString(wallet.userId()),
                wallet.availableBalance(),
                wallet.frozenBalance()
        );
    }
}

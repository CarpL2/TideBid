package io.github.carpl2.tidebid.account.application;

import java.math.BigDecimal;

public record WalletBalance(
        long userId,
        BigDecimal availableBalance,
        BigDecimal frozenBalance
) {
}

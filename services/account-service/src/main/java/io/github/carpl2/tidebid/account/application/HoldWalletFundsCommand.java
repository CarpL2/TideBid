package io.github.carpl2.tidebid.account.application;

import java.math.BigDecimal;

public record HoldWalletFundsCommand(
        String holdNo,
        long userId,
        BigDecimal amount
) {
}

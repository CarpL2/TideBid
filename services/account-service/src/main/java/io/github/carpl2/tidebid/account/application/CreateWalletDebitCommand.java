package io.github.carpl2.tidebid.account.application;

import java.math.BigDecimal;

public record CreateWalletDebitCommand(
        String paymentNo,
        long userId,
        long orderId,
        BigDecimal amount
) {
}

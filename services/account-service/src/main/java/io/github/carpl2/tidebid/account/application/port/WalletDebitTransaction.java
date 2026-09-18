package io.github.carpl2.tidebid.account.application.port;

import io.github.carpl2.tidebid.account.domain.WalletDebit;

import java.math.BigDecimal;

public interface WalletDebitTransaction {
    WalletDebit create(DebitData debitData);

    record DebitData(String paymentNo, long userId, long orderId, BigDecimal amount) {
    }
}

package io.github.carpl2.tidebid.account.application.port;

import io.github.carpl2.tidebid.account.domain.WalletDebit;

import java.util.Optional;

public interface WalletDebitRepository {
    Optional<WalletDebit> findByPaymentNo(String paymentNo);
}

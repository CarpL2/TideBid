package io.github.carpl2.tidebid.account.api;

import io.github.carpl2.tidebid.account.domain.WalletDebit;

import java.math.BigDecimal;
import java.time.Instant;

public record InternalWalletDebitResponse(
        String debitId,
        String paymentNo,
        String userId,
        String orderId,
        BigDecimal amount,
        String status,
        String failureCode,
        Instant decidedAt
) {
    static InternalWalletDebitResponse from(WalletDebit debit) {
        return new InternalWalletDebitResponse(
                Long.toString(debit.id()),
                debit.paymentNo(),
                Long.toString(debit.userId()),
                Long.toString(debit.orderId()),
                debit.amount(),
                debit.status().name(),
                debit.failureCode(),
                debit.decidedAt()
        );
    }
}

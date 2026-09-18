package io.github.carpl2.tidebid.trade.application;

import io.github.carpl2.tidebid.trade.application.port.AccountDebitPort;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradePaymentProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradePaymentRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

    @Test
    void queriesBeforeRetryAndAppliesExistingSuccessWithoutSecondDebit() {
        TradePaymentTransaction transaction = mock(TradePaymentTransaction.class);
        AccountDebitPort account = mock(AccountDebitPort.class);
        PaymentAttemptSnapshot attempt = attempt("UNKNOWN", 0, NOW);
        var claim = new TradePaymentTransaction.RecoveryClaim(attempt, "lease-token");
        var success = new AccountDebitPort.Succeeded(
                attempt.paymentNo(), attempt.buyerId(), attempt.orderId(), attempt.amount(), NOW);
        when(transaction.claimDue("worker-one")).thenReturn(List.of(claim));
        when(account.lookup(any())).thenReturn(new AccountDebitPort.Found(success));
        when(transaction.applyRecovered(eq(attempt.id()), eq("lease-token"), eq(success), any()))
                .thenReturn(attempt("SUCCEEDED", 1, null));

        var result = service(transaction, account).recoverBatch("worker-one");

        assertThat(result.succeeded()).isOne();
        verify(account, never()).debit(any());
    }

    @Test
    void retriesOnlyWhenAccountExplicitlyReportsMissingAndKeepsOriginalIdentity() {
        TradePaymentTransaction transaction = mock(TradePaymentTransaction.class);
        AccountDebitPort account = mock(AccountDebitPort.class);
        PaymentAttemptSnapshot attempt = attempt("PROCESSING", 0, null);
        var claim = new TradePaymentTransaction.RecoveryClaim(attempt, "lease-token");
        var rejection = new AccountDebitPort.Rejected(
                attempt.paymentNo(), attempt.buyerId(), attempt.orderId(), attempt.amount(),
                "INSUFFICIENT_BALANCE", NOW);
        when(transaction.claimDue("worker-two")).thenReturn(List.of(claim));
        when(account.lookup(any())).thenReturn(new AccountDebitPort.Missing());
        when(account.debit(any())).thenReturn(rejection);
        when(transaction.applyRecovered(eq(attempt.id()), eq("lease-token"), eq(rejection), any()))
                .thenReturn(attempt("REJECTED", 1, null));

        var result = service(transaction, account).recoverBatch("worker-two");

        assertThat(result.rejected()).isOne();
        ArgumentCaptor<AccountDebitPort.DebitCommand> command =
                ArgumentCaptor.forClass(AccountDebitPort.DebitCommand.class);
        verify(account).debit(command.capture());
        assertThat(command.getValue().paymentNo()).isEqualTo(attempt.paymentNo());
        assertThat(command.getValue().buyerId()).isEqualTo(attempt.buyerId());
        assertThat(command.getValue().orderId()).isEqualTo(attempt.orderId());
        assertThat(command.getValue().amount()).isEqualByComparingTo(attempt.amount());
        assertThat(command.getValue().requestId()).isEqualTo(attempt.requestId());
    }

    @Test
    void neverRetriesDebitWhenLookupOutcomeIsUnknown() {
        TradePaymentTransaction transaction = mock(TradePaymentTransaction.class);
        AccountDebitPort account = mock(AccountDebitPort.class);
        PaymentAttemptSnapshot attempt = attempt("UNKNOWN", 0, NOW);
        var claim = new TradePaymentTransaction.RecoveryClaim(attempt, "lease-token");
        when(transaction.claimDue("worker-three")).thenReturn(List.of(claim));
        when(account.lookup(any())).thenReturn(new AccountDebitPort.LookupUnknown());
        when(transaction.applyRecovered(eq(attempt.id()), eq("lease-token"), any(), any()))
                .thenReturn(attempt("UNKNOWN", 1, NOW.plusSeconds(5)));

        var result = service(transaction, account).recoverBatch("worker-three");

        assertThat(result.pending()).isOne();
        verify(account, never()).debit(any());
    }

    private static TradePaymentRecoveryService service(
            TradePaymentTransaction transaction, AccountDebitPort account
    ) {
        return new TradePaymentRecoveryService(transaction, account,
                new TradePaymentProperties(true, Duration.ofSeconds(5), Duration.ofSeconds(5),
                        Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(5), 20, 12));
    }

    private static PaymentAttemptSnapshot attempt(String status, int recoveryCount, Instant nextRecoveryAt) {
        String failureCode = "REJECTED".equals(status) ? "INSUFFICIENT_BALANCE" : null;
        Instant completedAt = "SUCCEEDED".equals(status) || "REJECTED".equals(status) ? NOW : null;
        return new PaymentAttemptSnapshot(
                7001L, "PAY:7001", 6001L, 5001L, "request_0001", new BigDecimal("100.00"),
                status, failureCode, recoveryCount, nextRecoveryAt, completedAt,
                NOW.minusSeconds(60), NOW
        );
    }
}

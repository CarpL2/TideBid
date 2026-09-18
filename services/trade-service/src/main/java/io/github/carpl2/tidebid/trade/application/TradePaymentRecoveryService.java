package io.github.carpl2.tidebid.trade.application;

import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.trade.application.port.AccountDebitPort;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradePaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Profile({"local-db", "nacos"})
public class TradePaymentRecoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TradePaymentRecoveryService.class);

    private final TradePaymentTransaction transaction;
    private final AccountDebitPort account;
    private final TradePaymentProperties properties;

    public TradePaymentRecoveryService(
            TradePaymentTransaction transaction,
            AccountDebitPort account,
            TradePaymentProperties properties
    ) {
        this.transaction = transaction;
        this.account = account;
        this.properties = properties;
    }

    public RecoveryResult recoverBatch(String leaseOwner) {
        List<TradePaymentTransaction.RecoveryClaim> claims = transaction.claimDue(leaseOwner);
        int succeeded = 0;
        int rejected = 0;
        int pending = 0;
        int exhausted = 0;
        for (TradePaymentTransaction.RecoveryClaim claim : claims) {
            PaymentAttemptSnapshot result = recover(claim);
            if ("SUCCEEDED".equals(result.status())) {
                succeeded++;
            } else if ("REJECTED".equals(result.status())) {
                rejected++;
            } else if (result.recoveryCount() >= properties.maximumAttempts()
                    && result.nextRecoveryAt() == null) {
                exhausted++;
                LOGGER.error(
                        "Payment recovery exhausted paymentNo={} attempts={} status={}",
                        result.paymentNo(), result.recoveryCount(), result.status()
                );
            } else {
                pending++;
            }
        }
        return new RecoveryResult(claims.size(), succeeded, rejected, pending, exhausted);
    }

    private PaymentAttemptSnapshot recover(TradePaymentTransaction.RecoveryClaim claim) {
        PaymentAttemptSnapshot attempt = claim.attempt();
        String traceId = TraceIds.create();
        AccountDebitPort.DebitLookup lookup = lookup(attempt.paymentNo(), traceId);
        AccountDebitPort.DebitResult result;
        if (lookup instanceof AccountDebitPort.Found found) {
            result = found.result();
        } else if (lookup instanceof AccountDebitPort.Missing) {
            result = debit(attempt, traceId);
        } else {
            result = new AccountDebitPort.Unknown();
        }
        try {
            return transaction.applyRecovered(attempt.id(), claim.leaseToken(), result, traceId);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Payment recovery result could not be applied paymentNo={} errorCode={}",
                    attempt.paymentNo(), exception.getClass().getSimpleName().toUpperCase()
            );
            return transaction.applyRecovered(
                    attempt.id(), claim.leaseToken(), new AccountDebitPort.Unknown(), traceId);
        }
    }

    private AccountDebitPort.DebitLookup lookup(String paymentNo, String traceId) {
        try {
            AccountDebitPort.DebitLookup result = account.lookup(
                    new AccountDebitPort.DebitLookupQuery(paymentNo, traceId));
            return result == null ? new AccountDebitPort.LookupUnknown() : result;
        } catch (RuntimeException exception) {
            return new AccountDebitPort.LookupUnknown();
        }
    }

    private AccountDebitPort.DebitResult debit(PaymentAttemptSnapshot attempt, String traceId) {
        try {
            AccountDebitPort.DebitResult result = account.debit(new AccountDebitPort.DebitCommand(
                    attempt.paymentNo(), attempt.buyerId(), attempt.orderId(), attempt.amount(),
                    attempt.requestId(), traceId));
            return result == null ? new AccountDebitPort.Unknown() : result;
        } catch (RuntimeException exception) {
            return new AccountDebitPort.Unknown();
        }
    }

    public record RecoveryResult(int claimed, int succeeded, int rejected, int pending, int exhausted) {
        public RecoveryResult {
            if (claimed < 0 || succeeded < 0 || rejected < 0 || pending < 0 || exhausted < 0
                    || succeeded + rejected + pending + exhausted != claimed) {
                throw new IllegalArgumentException("payment recovery result counts are inconsistent");
            }
        }
    }
}

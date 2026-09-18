package io.github.carpl2.tidebid.trade.application;

import io.github.carpl2.tidebid.core.TraceIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@Profile({"local-db", "nacos"})
public class TradePaymentTimeoutService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TradePaymentTimeoutService.class);

    private final TradePaymentTimeoutTransaction transaction;
    private final TradePaymentRecoveryService recovery;
    private final String recoveryOwner = "timeout-" + UUID.randomUUID();

    public TradePaymentTimeoutService(
            TradePaymentTimeoutTransaction transaction,
            TradePaymentRecoveryService recovery
    ) {
        this.transaction = transaction;
        this.recovery = recovery;
    }

    public TradePaymentTimeoutTransaction.TimeoutResult fromMessage(
            TradePaymentTimeoutTransaction.MessageCommand command
    ) {
        recoverProcessing(command.orderId(), command.expectedDeadline());
        return transaction.fromMessage(command);
    }

    public ScanResult scanDue() {
        int timedOut = 0;
        int recovered = 0;
        int deferred = 0;
        int unchanged = 0;
        for (TradePaymentTimeoutTransaction.DueOrder due : transaction.findDue()) {
            try {
                if (recoverProcessing(due.orderId(), due.expectedDeadline())) {
                    recovered++;
                }
                var result = transaction.fromDatabaseScan(
                        due.orderId(), due.expectedDeadline(), TraceIds.create());
                if (result == TradePaymentTimeoutTransaction.TimeoutResult.TIMED_OUT) {
                    timedOut++;
                } else if (result == TradePaymentTimeoutTransaction.TimeoutResult.PROCESSING) {
                    deferred++;
                } else {
                    unchanged++;
                }
            } catch (RuntimeException exception) {
                deferred++;
                LOGGER.warn("Payment timeout scan deferred: orderId={}, errorCode={}",
                        due.orderId(), exception.getClass().getSimpleName().toUpperCase());
            }
        }
        return new ScanResult(timedOut, recovered, deferred, unchanged);
    }

    private boolean recoverProcessing(long orderId, Instant expectedDeadline) {
        if (!transaction.requiresRecovery(orderId, expectedDeadline)) {
            return false;
        }
        recovery.recoverBatch(recoveryOwner);
        return true;
    }

    public record ScanResult(int timedOut, int recoveryTriggered, int deferred, int unchanged) {
        public ScanResult {
            if (timedOut < 0 || recoveryTriggered < 0 || deferred < 0 || unchanged < 0) {
                throw new IllegalArgumentException("timeout scan counts must not be negative");
            }
        }
    }
}

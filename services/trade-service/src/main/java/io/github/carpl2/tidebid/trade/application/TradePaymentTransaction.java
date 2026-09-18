package io.github.carpl2.tidebid.trade.application;

import io.github.carpl2.tidebid.contracts.OrderPaidEvent;
import io.github.carpl2.tidebid.contracts.OrderPaymentTimedOutEvent;
import io.github.carpl2.tidebid.contracts.SellerCreditReason;
import io.github.carpl2.tidebid.contracts.SellerCreditRequestedEvent;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.trade.application.port.AccountDebitPort;
import io.github.carpl2.tidebid.trade.application.port.TradeIdGenerator;
import io.github.carpl2.tidebid.trade.domain.TradeErrorCode;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradePaymentProperties;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.JdbcTradeOutboxRepository;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.TradeOutboxEventFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Profile({"local-db", "nacos"})
public class TradePaymentTransaction {

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,48}");

    private final JdbcTemplate jdbc;
    private final TradeIdGenerator ids;
    private final JdbcTradeOutboxRepository outbox;
    private final TradeOutboxEventFactory events;
    private final TradePaymentProperties properties;
    private final Clock clock;

    public TradePaymentTransaction(
            DataSource dataSource,
            TradeIdGenerator ids,
            JdbcTradeOutboxRepository outbox,
            TradeOutboxEventFactory events,
            TradePaymentProperties properties,
            Clock clock
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.ids = ids;
        this.outbox = outbox;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public StartResult begin(long buyerId, long orderId, String requestId) {
        if (buyerId <= 0 || orderId <= 0 || requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException("buyerId, orderId or requestId is invalid");
        }
        PaymentAttemptSnapshot existing = findByBuyerRequest(buyerId, requestId, false);
        if (existing != null) {
            return existing(existing, orderId);
        }

        OrderRow order = lockOrder(orderId);
        requireBuyer(order, buyerId);

        // A duplicate request can have committed while this transaction waited for the order row.
        existing = findByBuyerRequest(buyerId, requestId, true);
        if (existing != null) {
            return existing(existing, orderId);
        }
        if (!"PENDING_PAYMENT".equals(order.status())) {
            throw new BusinessException("PAYMENT_PROCESSING".equals(order.status())
                    ? TradeErrorCode.PAYMENT_CONCURRENT_CONFLICT : TradeErrorCode.ORDER_NOT_PAYABLE);
        }
        Instant now = clock.instant();
        if (order.paymentDeadline() == null || !now.isBefore(order.paymentDeadline())) {
            throw new BusinessException(TradeErrorCode.PAYMENT_DEADLINE_EXPIRED);
        }
        if (order.payableAmount() == null || order.payableAmount().signum() <= 0) {
            throw new BusinessException(TradeErrorCode.ORDER_NOT_PAYABLE);
        }

        long attemptId = ids.nextId();
        String paymentNo = "PAY:" + attemptId;
        try {
            jdbc.update("""
                    INSERT INTO payment_attempt (
                        id, payment_no, order_id, buyer_id, request_id, amount, status,
                        recovery_count, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING', 0, ?, ?)
                    """, attemptId, paymentNo, order.id(), buyerId, requestId, order.payableAmount(),
                    Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(TradeErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
        }
        int changed = jdbc.update("""
                UPDATE trade_order SET status = 'PAYMENT_PROCESSING', version = version + 1, updated_at = ?
                WHERE id = ? AND status = 'PENDING_PAYMENT' AND version = ?
                """, Timestamp.from(now), order.id(), order.version());
        if (changed != 1) {
            throw new BusinessException(TradeErrorCode.PAYMENT_CONCURRENT_CONFLICT);
        }
        return new StartResult(findById(attemptId, false), true);
    }

    @Transactional
    public PaymentAttemptSnapshot apply(long attemptId, AccountDebitPort.DebitResult result, String traceId) {
        PaymentAttemptSnapshot attempt = findById(attemptId, true);
        if ("SUCCEEDED".equals(attempt.status()) || "REJECTED".equals(attempt.status())) {
            return attempt;
        }
        if (result instanceof AccountDebitPort.Unknown) {
            return markUnknown(attempt);
        }
        return applyDefinite(attempt, result, traceId, false);
    }

    @Transactional
    public List<RecoveryClaim> claimDue(String leaseOwner) {
        if (leaseOwner == null || leaseOwner.isBlank() || leaseOwner.length() > 64) {
            throw new IllegalArgumentException("leaseOwner must contain 1 to 64 characters");
        }
        Instant now = clock.instant();
        Instant processingCutoff = now.minus(properties.initialRecoveryDelay());
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM payment_attempt
                WHERE recovery_count < ?
                  AND (lease_until IS NULL OR lease_until <= ?)
                  AND ((status = 'UNKNOWN' AND next_recovery_at IS NOT NULL AND next_recovery_at <= ?)
                    OR (status = 'PROCESSING' AND next_recovery_at IS NULL AND updated_at <= ?))
                ORDER BY COALESCE(next_recovery_at, updated_at), id
                LIMIT ?
                """, Long.class, properties.maximumAttempts(), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(processingCutoff), properties.batchSize());
        List<RecoveryClaim> claimed = new ArrayList<>();
        for (Long id : ids) {
            String token = UUID.randomUUID().toString();
            int changed = jdbc.update("""
                    UPDATE payment_attempt
                    SET lease_owner = ?, lease_token = ?, lease_until = ?
                    WHERE id = ? AND recovery_count < ?
                      AND (lease_until IS NULL OR lease_until <= ?)
                      AND ((status = 'UNKNOWN' AND next_recovery_at IS NOT NULL AND next_recovery_at <= ?)
                        OR (status = 'PROCESSING' AND next_recovery_at IS NULL AND updated_at <= ?))
                    """, leaseOwner, token, Timestamp.from(now.plus(properties.leaseDuration())), id,
                    properties.maximumAttempts(), Timestamp.from(now), Timestamp.from(now),
                    Timestamp.from(processingCutoff));
            if (changed == 1) {
                claimed.add(new RecoveryClaim(findById(id, false), token));
            }
        }
        return List.copyOf(claimed);
    }

    @Transactional
    public PaymentAttemptSnapshot applyRecovered(
            long attemptId, String leaseToken, AccountDebitPort.DebitResult result, String traceId
    ) {
        RecoveryRow recovery = findRecoveryRow(attemptId, true);
        PaymentAttemptSnapshot attempt = recovery.attempt();
        if ("SUCCEEDED".equals(attempt.status()) || "REJECTED".equals(attempt.status())) {
            return attempt;
        }
        if (leaseToken == null || !leaseToken.equals(recovery.leaseToken())) {
            return attempt;
        }
        if (result instanceof AccountDebitPort.Unknown) {
            return scheduleRecovery(attempt, leaseToken);
        }
        return applyDefinite(attempt, result, traceId, true);
    }

    private PaymentAttemptSnapshot applyDefinite(
            PaymentAttemptSnapshot attempt, AccountDebitPort.DebitResult result,
            String traceId, boolean recovered
    ) {
        OrderRow order = lockOrder(attempt.orderId());
        if (!"PAYMENT_PROCESSING".equals(order.status())) {
            return attempt;
        }
        Instant now = clock.instant();
        if (result instanceof AccountDebitPort.Succeeded success) {
            requireMatching(attempt, success.paymentNo(), success.buyerId(), success.orderId(), success.amount());
            return succeed(attempt, order, traceId, now, recovered);
        }
        AccountDebitPort.Rejected rejected = (AccountDebitPort.Rejected) result;
        requireMatching(attempt, rejected.paymentNo(), rejected.buyerId(), rejected.orderId(), rejected.amount());
        return reject(attempt, order, rejected.failureCode(), traceId, now, recovered);
    }

    private PaymentAttemptSnapshot succeed(
            PaymentAttemptSnapshot attempt, OrderRow order, String traceId, Instant now, boolean recovered
    ) {
        String creditNo = "SC:" + order.id() + ":SALE";
        int orderChanged = jdbc.update("""
                UPDATE trade_order
                SET status = 'PAID', paid_at = ?, seller_settlement_status = 'PENDING',
                    seller_credit_no = ?, seller_receivable_amount = final_price,
                    version = version + 1, updated_at = ?
                WHERE id = ? AND status = 'PAYMENT_PROCESSING' AND version = ?
                """, Timestamp.from(now), creditNo, Timestamp.from(now), order.id(), order.version());
        if (orderChanged != 1) {
            throw new BusinessException(TradeErrorCode.PAYMENT_CONCURRENT_CONFLICT);
        }
        jdbc.update("""
                UPDATE payment_attempt
                SET status = 'SUCCEEDED', failure_code = NULL, next_recovery_at = NULL,
                    recovery_count = recovery_count + ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                    completed_at = ?, updated_at = ?
                WHERE id = ? AND status IN ('PROCESSING', 'UNKNOWN')
                """, recovered ? 1 : 0, Timestamp.from(now), Timestamp.from(now), attempt.id());

        OrderPaidEvent paid = new OrderPaidEvent(
                order.id(), order.orderNo(), order.auctionId(), order.sellerId(), order.buyerId(),
                attempt.paymentNo(), order.finalPrice(), order.capturedDepositAmount(),
                order.payableAmount(), now);
        SellerCreditRequestedEvent credit = new SellerCreditRequestedEvent(
                SellerCreditReason.SALE_PROCEEDS, creditNo, order.id(), order.orderNo(),
                order.auctionId(), order.sellerId(), order.finalPrice(), order.capturedDepositAmount(),
                order.finalPrice(), now, now);
        outbox.enqueue(events.orderPaid(paid, traceId, now), now);
        outbox.enqueue(events.sellerCreditRequested(credit, traceId, now), now);
        return findById(attempt.id(), false);
    }

    private PaymentAttemptSnapshot reject(
            PaymentAttemptSnapshot attempt, OrderRow order, String failureCode, String traceId,
            Instant now, boolean recovered
    ) {
        String safeCode = safeFailureCode(failureCode);
        jdbc.update("""
                UPDATE payment_attempt
                SET status = 'REJECTED', failure_code = ?, next_recovery_at = NULL,
                    recovery_count = recovery_count + ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                    completed_at = ?, updated_at = ?
                WHERE id = ? AND status IN ('PROCESSING', 'UNKNOWN')
                """, safeCode, recovered ? 1 : 0,
                Timestamp.from(now), Timestamp.from(now), attempt.id());
        if (order.paymentDeadline() != null && !now.isBefore(order.paymentDeadline())) {
            timeoutAfterRejected(order, traceId, now);
        } else {
            int orderChanged = jdbc.update("""
                    UPDATE trade_order SET status = 'PENDING_PAYMENT', version = version + 1, updated_at = ?
                    WHERE id = ? AND status = 'PAYMENT_PROCESSING' AND version = ?
                    """, Timestamp.from(now), order.id(), order.version());
            if (orderChanged != 1) {
                throw new BusinessException(TradeErrorCode.PAYMENT_CONCURRENT_CONFLICT);
            }
        }
        return findById(attempt.id(), false);
    }

    private void timeoutAfterRejected(OrderRow order, String traceId, Instant now) {
        String creditNo = "SC:" + order.id() + ":DEFAULT";
        int orderChanged = jdbc.update("""
                UPDATE trade_order
                SET status = 'PAYMENT_TIMEOUT', timed_out_at = ?,
                    seller_settlement_status = 'PENDING', seller_credit_no = ?,
                    seller_receivable_amount = captured_deposit_amount,
                    version = version + 1, updated_at = ?
                WHERE id = ? AND status = 'PAYMENT_PROCESSING' AND version = ?
                """, Timestamp.from(now), creditNo, Timestamp.from(now), order.id(), order.version());
        if (orderChanged != 1) {
            throw new BusinessException(TradeErrorCode.PAYMENT_CONCURRENT_CONFLICT);
        }

        OrderPaymentTimedOutEvent timedOut = new OrderPaymentTimedOutEvent(
                order.id(), order.orderNo(), order.auctionId(), order.sellerId(), order.buyerId(),
                order.finalPrice(), order.capturedDepositAmount(), order.payableAmount(),
                order.paymentDeadline(), now);
        SellerCreditRequestedEvent credit = new SellerCreditRequestedEvent(
                SellerCreditReason.DEFAULT_COMPENSATION, creditNo, order.id(), order.orderNo(),
                order.auctionId(), order.sellerId(), order.finalPrice(), order.capturedDepositAmount(),
                order.capturedDepositAmount(), now, now);
        outbox.enqueue(events.orderPaymentTimedOut(timedOut, traceId, now), now);
        outbox.enqueue(events.sellerCreditRequested(credit, traceId, now), now);
    }

    private PaymentAttemptSnapshot markUnknown(PaymentAttemptSnapshot attempt) {
        if ("UNKNOWN".equals(attempt.status())) {
            return attempt;
        }
        Instant now = clock.instant();
        jdbc.update("""
                UPDATE payment_attempt
                SET status = 'UNKNOWN', next_recovery_at = ?, updated_at = ?
                WHERE id = ? AND status = 'PROCESSING'
                """, Timestamp.from(now.plus(properties.initialRecoveryDelay())),
                Timestamp.from(now), attempt.id());
        return findById(attempt.id(), false);
    }

    private PaymentAttemptSnapshot scheduleRecovery(PaymentAttemptSnapshot attempt, String leaseToken) {
        Instant now = clock.instant();
        int completedAttempts = attempt.recoveryCount() + 1;
        if (completedAttempts >= properties.maximumAttempts()) {
            jdbc.update("""
                    UPDATE payment_attempt
                    SET status = 'PROCESSING', recovery_count = recovery_count + 1,
                        next_recovery_at = NULL, lease_owner = NULL, lease_token = NULL,
                        lease_until = NULL, updated_at = ?
                    WHERE id = ? AND status IN ('PROCESSING', 'UNKNOWN') AND lease_token = ?
                    """, Timestamp.from(now), attempt.id(), leaseToken);
        } else {
            jdbc.update("""
                    UPDATE payment_attempt
                    SET status = 'UNKNOWN', recovery_count = recovery_count + 1,
                        next_recovery_at = ?, lease_owner = NULL, lease_token = NULL,
                        lease_until = NULL, updated_at = ?
                    WHERE id = ? AND status IN ('PROCESSING', 'UNKNOWN') AND lease_token = ?
                    """, Timestamp.from(now.plus(retryDelay(completedAttempts))), Timestamp.from(now),
                    attempt.id(), leaseToken);
        }
        return findById(attempt.id(), false);
    }

    private Duration retryDelay(int completedAttempts) {
        Duration delay = properties.initialRetryDelay();
        for (int attempt = 1;
             attempt < completedAttempts && delay.compareTo(properties.maximumRetryDelay()) < 0;
             attempt++) {
            Duration doubled = delay.multipliedBy(2);
            delay = doubled.compareTo(properties.maximumRetryDelay()) > 0
                    ? properties.maximumRetryDelay() : doubled;
        }
        return delay;
    }

    private StartResult existing(PaymentAttemptSnapshot attempt, long orderId) {
        if (attempt.orderId() != orderId) {
            throw new BusinessException(TradeErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
        }
        return new StartResult(attempt, "PROCESSING".equals(attempt.status()));
    }

    private PaymentAttemptSnapshot findByBuyerRequest(long buyerId, String requestId, boolean lock) {
        List<PaymentAttemptSnapshot> rows = jdbc.query("""
                SELECT id, payment_no, order_id, buyer_id, request_id, amount, status,
                       failure_code, recovery_count, next_recovery_at, completed_at, created_at, updated_at
                FROM payment_attempt WHERE buyer_id = ? AND request_id = ?
                """ + (lock ? " FOR UPDATE" : ""),
                (row, number) -> mapAttempt(row), buyerId, requestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private PaymentAttemptSnapshot findById(long id, boolean lock) {
        return jdbc.query("""
                SELECT id, payment_no, order_id, buyer_id, request_id, amount, status,
                       failure_code, recovery_count, next_recovery_at, completed_at, created_at, updated_at
                FROM payment_attempt WHERE id = ?
                """ + (lock ? " FOR UPDATE" : ""),
                (row, number) -> mapAttempt(row), id).getFirst();
    }

    private RecoveryRow findRecoveryRow(long id, boolean lock) {
        return jdbc.query("""
                SELECT id, payment_no, order_id, buyer_id, request_id, amount, status,
                       failure_code, recovery_count, next_recovery_at, completed_at, created_at, updated_at,
                       lease_token
                FROM payment_attempt WHERE id = ?
                """ + (lock ? " FOR UPDATE" : ""),
                (row, number) -> new RecoveryRow(mapAttempt(row), row.getString("lease_token")), id).getFirst();
    }

    private OrderRow lockOrder(long orderId) {
        List<OrderRow> rows = jdbc.query("""
                SELECT id, order_no, auction_id, seller_id, buyer_id, final_price,
                       captured_deposit_amount, payable_amount, status, payment_deadline, version
                FROM trade_order WHERE id = ? FOR UPDATE
                """, (row, number) -> new OrderRow(
                row.getLong("id"), row.getString("order_no"), row.getLong("auction_id"),
                row.getLong("seller_id"), row.getLong("buyer_id"), row.getBigDecimal("final_price"),
                row.getBigDecimal("captured_deposit_amount"), row.getBigDecimal("payable_amount"),
                row.getString("status"), instant(row, "payment_deadline"), row.getLong("version")), orderId);
        if (rows.isEmpty()) {
            throw new BusinessException(TradeErrorCode.ORDER_NOT_FOUND);
        }
        return rows.getFirst();
    }

    private static PaymentAttemptSnapshot mapAttempt(ResultSet row) throws SQLException {
        return new PaymentAttemptSnapshot(
                row.getLong("id"), row.getString("payment_no"), row.getLong("order_id"),
                row.getLong("buyer_id"), row.getString("request_id"), row.getBigDecimal("amount"),
                row.getString("status"), row.getString("failure_code"),
                row.getInt("recovery_count"),
                instant(row, "next_recovery_at"), instant(row, "completed_at"),
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void requireBuyer(OrderRow order, long buyerId) {
        if (order.buyerId() != buyerId) {
            throw new BusinessException(TradeErrorCode.ORDER_FORBIDDEN);
        }
    }

    private static void requireMatching(
            PaymentAttemptSnapshot attempt, String paymentNo, long buyerId, long orderId, BigDecimal amount
    ) {
        if (!attempt.paymentNo().equals(paymentNo) || attempt.buyerId() != buyerId
                || attempt.orderId() != orderId || attempt.amount().compareTo(amount) != 0) {
            throw new IllegalStateException("Account debit response does not match the payment attempt");
        }
    }

    private static String safeFailureCode(String value) {
        String code = value == null ? "ACCOUNT_DEBIT_REJECTED" : value.trim();
        return code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "ACCOUNT_DEBIT_REJECTED";
    }

    public record StartResult(PaymentAttemptSnapshot attempt, boolean shouldCallAccount) {
    }

    public record RecoveryClaim(PaymentAttemptSnapshot attempt, String leaseToken) {
    }

    private record RecoveryRow(PaymentAttemptSnapshot attempt, String leaseToken) {
    }

    private record OrderRow(
            long id, String orderNo, long auctionId, long sellerId, long buyerId,
            BigDecimal finalPrice, BigDecimal capturedDepositAmount, BigDecimal payableAmount,
            String status, Instant paymentDeadline, long version
    ) {
    }
}

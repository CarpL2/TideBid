package io.github.carpl2.tidebid.trade.application;

import io.github.carpl2.tidebid.contracts.OrderPaymentTimedOutEvent;
import io.github.carpl2.tidebid.contracts.SellerCreditReason;
import io.github.carpl2.tidebid.contracts.SellerCreditRequestedEvent;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradePaymentTimeoutProperties;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.JdbcTradeInboxRepository;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.JdbcTradeOutboxRepository;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.TradeOutboxEventFactory;
import org.springframework.context.annotation.Profile;
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
import java.util.List;
import java.util.UUID;

@Service
@Profile({"local-db", "nacos"})
public class TradePaymentTimeoutTransaction {

    private final JdbcTemplate jdbc;
    private final JdbcTradeInboxRepository inbox;
    private final JdbcTradeOutboxRepository outbox;
    private final TradeOutboxEventFactory events;
    private final TradePaymentTimeoutProperties properties;
    private final Clock clock;

    public TradePaymentTimeoutTransaction(
            DataSource dataSource,
            JdbcTradeInboxRepository inbox,
            JdbcTradeOutboxRepository outbox,
            TradeOutboxEventFactory events,
            TradePaymentTimeoutProperties properties,
            Clock clock
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.inbox = inbox;
        this.outbox = outbox;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    public List<DueOrder> findDue() {
        Instant now = clock.instant();
        return jdbc.query("""
                SELECT id, payment_deadline
                FROM trade_order
                WHERE status IN ('PENDING_PAYMENT', 'PAYMENT_PROCESSING')
                  AND payment_deadline <= ?
                ORDER BY payment_deadline, id
                LIMIT ?
                """, (row, number) -> new DueOrder(
                        row.getLong("id"), row.getTimestamp("payment_deadline").toInstant()),
                Timestamp.from(now), properties.batchSize());
    }

    public boolean requiresRecovery(long orderId, Instant expectedDeadline) {
        List<String> statuses = jdbc.queryForList("""
                SELECT status FROM trade_order
                WHERE id = ? AND payment_deadline = ?
                """, String.class, orderId, Timestamp.from(expectedDeadline));
        return statuses.size() == 1 && "PAYMENT_PROCESSING".equals(statuses.getFirst());
    }

    @Transactional
    public TimeoutResult fromDatabaseScan(long orderId, Instant expectedDeadline, String traceId) {
        return expire(orderId, expectedDeadline, traceId);
    }

    @Transactional
    public TimeoutResult fromMessage(MessageCommand message) {
        JdbcTradeInboxRepository.InboxDecision decision = inbox.recordProcessed(
                new JdbcTradeInboxRepository.InboxEntity(
                        message.consumerName(), message.eventId().toString(), message.eventType(),
                        message.schemaVersion(), message.payloadHash(), clock.instant()));
        if (decision == JdbcTradeInboxRepository.InboxDecision.DUPLICATE) {
            return TimeoutResult.DUPLICATE;
        }
        TimeoutResult result = expire(message.orderId(), message.expectedDeadline(), message.traceId());
        if (result == TimeoutResult.PROCESSING || result == TimeoutResult.EARLY) {
            throw new TimeoutDeferredException(result);
        }
        return result;
    }

    private TimeoutResult expire(long orderId, Instant expectedDeadline, String traceId) {
        OrderRow order = lockOrder(orderId);
        if (!order.paymentDeadline().equals(expectedDeadline)) {
            throw new IllegalStateException("payment timeout command deadline does not match the order");
        }
        Instant now = clock.instant();
        if (now.isBefore(order.paymentDeadline())) {
            return TimeoutResult.EARLY;
        }
        return switch (order.status()) {
            case "PENDING_PAYMENT" -> timeout(order, traceId, now);
            case "PAYMENT_PROCESSING" -> TimeoutResult.PROCESSING;
            case "PAID" -> TimeoutResult.ALREADY_PAID;
            case "PAYMENT_TIMEOUT" -> TimeoutResult.ALREADY_TIMED_OUT;
            default -> throw new IllegalStateException("order is not eligible for payment timeout");
        };
    }

    private TimeoutResult timeout(OrderRow order, String traceId, Instant now) {
        String creditNo = "SC:" + order.id() + ":DEFAULT";
        int changed = jdbc.update("""
                UPDATE trade_order
                SET status = 'PAYMENT_TIMEOUT', timed_out_at = ?,
                    seller_settlement_status = 'PENDING', seller_credit_no = ?,
                    seller_receivable_amount = captured_deposit_amount,
                    version = version + 1, updated_at = ?
                WHERE id = ? AND status = 'PENDING_PAYMENT' AND version = ?
                """, Timestamp.from(now), creditNo, Timestamp.from(now), order.id(), order.version());
        if (changed != 1) {
            return TimeoutResult.LOST_RACE;
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
        return TimeoutResult.TIMED_OUT;
    }

    private OrderRow lockOrder(long orderId) {
        List<OrderRow> rows = jdbc.query("""
                SELECT id, order_no, auction_id, seller_id, buyer_id, final_price,
                       captured_deposit_amount, payable_amount, status, payment_deadline, version
                FROM trade_order WHERE id = ? FOR UPDATE
                """, (row, number) -> mapOrder(row), orderId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("payment timeout order does not exist");
        }
        return rows.getFirst();
    }

    private static OrderRow mapOrder(ResultSet row) throws SQLException {
        return new OrderRow(
                row.getLong("id"), row.getString("order_no"), row.getLong("auction_id"),
                row.getLong("seller_id"), row.getLong("buyer_id"), row.getBigDecimal("final_price"),
                row.getBigDecimal("captured_deposit_amount"), row.getBigDecimal("payable_amount"),
                row.getString("status"), row.getTimestamp("payment_deadline").toInstant(),
                row.getLong("version"));
    }

    public record DueOrder(long orderId, Instant expectedDeadline) {
    }

    public record MessageCommand(
            String consumerName, UUID eventId, String eventType, int schemaVersion,
            String payloadHash, long orderId, Instant expectedDeadline, String traceId
    ) {
    }

    public enum TimeoutResult {
        TIMED_OUT,
        PROCESSING,
        EARLY,
        ALREADY_PAID,
        ALREADY_TIMED_OUT,
        LOST_RACE,
        DUPLICATE
    }

    public static final class TimeoutDeferredException extends RuntimeException {
        private final TimeoutResult reason;

        public TimeoutDeferredException(TimeoutResult reason) {
            super("payment timeout processing is deferred: " + reason);
            this.reason = reason;
        }

        public TimeoutResult reason() {
            return reason;
        }
    }

    private record OrderRow(
            long id, String orderNo, long auctionId, long sellerId, long buyerId,
            BigDecimal finalPrice, BigDecimal capturedDepositAmount, BigDecimal payableAmount,
            String status, Instant paymentDeadline, long version
    ) {
    }
}

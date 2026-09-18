package io.github.carpl2.tidebid.trade.application;

import io.github.carpl2.tidebid.contracts.DepositSettlementType;
import io.github.carpl2.tidebid.contracts.OrderPaidEvent;
import io.github.carpl2.tidebid.contracts.OrderPaymentTimeoutCommand;
import io.github.carpl2.tidebid.contracts.SellerCreditReason;
import io.github.carpl2.tidebid.contracts.SellerCreditRequestedEvent;
import io.github.carpl2.tidebid.contracts.WalletHoldSettledEvent;
import io.github.carpl2.tidebid.contracts.WalletHoldSettlementStatus;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeOrderProperties;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.JdbcTradeOutboxRepository;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.TradeOutboxEventFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@Profile({"local-db", "nacos"})
public class TradeDepositSettlementService {

    private final JdbcTemplate jdbc;
    private final TradeOrderProperties properties;
    private final JdbcTradeOutboxRepository outbox;
    private final TradeOutboxEventFactory events;
    private final Clock clock;

    public TradeDepositSettlementService(
            DataSource dataSource,
            TradeOrderProperties properties,
            JdbcTradeOutboxRepository outbox,
            TradeOutboxEventFactory events,
            Clock clock
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.properties = properties;
        this.outbox = outbox;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void apply(WalletHoldSettledEvent result, String traceId) {
        requireCapture(result);
        OrderRow order = lockOrder(result.orderId());
        validateSnapshot(order, result);
        BigDecimal payableAmount = result.captureTargetAmount().subtract(result.capturedAmount());

        if (!"PENDING_DEPOSIT".equals(order.status())) {
            validateExistingResult(order, result.capturedAmount(), payableAmount);
            return;
        }

        Instant now = clock.instant();
        if (payableAmount.signum() > 0) {
            moveToPendingPayment(order, result.capturedAmount(), payableAmount, traceId, now);
        } else {
            moveDirectlyToPaid(order, result.capturedAmount(), traceId, now);
        }
    }

    private void moveToPendingPayment(
            OrderRow order,
            BigDecimal capturedAmount,
            BigDecimal payableAmount,
            String traceId,
            Instant now
    ) {
        Instant deadline = now.plus(properties.paymentWindow());
        int changed = jdbc.update("""
                UPDATE trade_order
                SET captured_deposit_amount = ?, payable_amount = ?, status = 'PENDING_PAYMENT',
                    payment_deadline = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND status = 'PENDING_DEPOSIT' AND version = ?
                """, capturedAmount, payableAmount, Timestamp.from(deadline), Timestamp.from(now),
                order.id(), order.version());
        requireSingleChange(changed);
        outbox.enqueue(events.paymentTimeout(
                new OrderPaymentTimeoutCommand(order.id(), deadline), traceId, now), now);
    }

    private void moveDirectlyToPaid(
            OrderRow order,
            BigDecimal capturedAmount,
            String traceId,
            Instant now
    ) {
        String creditNo = "SC:" + order.id() + ":SALE";
        int changed = jdbc.update("""
                UPDATE trade_order
                SET captured_deposit_amount = ?, payable_amount = 0.00, status = 'PAID', paid_at = ?,
                    seller_settlement_status = 'PENDING', seller_credit_no = ?,
                    seller_receivable_amount = final_price, version = version + 1, updated_at = ?
                WHERE id = ? AND status = 'PENDING_DEPOSIT' AND version = ?
                """, capturedAmount, Timestamp.from(now), creditNo, Timestamp.from(now),
                order.id(), order.version());
        requireSingleChange(changed);

        OrderPaidEvent paid = new OrderPaidEvent(
                order.id(), order.orderNo(), order.auctionId(), order.sellerId(), order.buyerId(), null,
                order.finalPrice(), capturedAmount, BigDecimal.ZERO.setScale(2), now);
        SellerCreditRequestedEvent credit = new SellerCreditRequestedEvent(
                SellerCreditReason.SALE_PROCEEDS, creditNo, order.id(), order.orderNo(), order.auctionId(),
                order.sellerId(), order.finalPrice(), capturedAmount, order.finalPrice(), now, now);
        outbox.enqueue(events.orderPaid(paid, traceId, now), now);
        outbox.enqueue(events.sellerCreditRequested(credit, traceId, now), now);
    }

    private OrderRow lockOrder(long orderId) {
        List<OrderRow> rows = jdbc.query("""
                SELECT id, order_no, auction_id, seller_id, buyer_id, winner_hold_no,
                       winner_hold_amount, final_price, captured_deposit_amount, payable_amount,
                       status, version
                FROM trade_order WHERE id = ? FOR UPDATE
                """, (row, number) -> new OrderRow(
                row.getLong("id"), row.getString("order_no"), row.getLong("auction_id"),
                row.getLong("seller_id"), row.getLong("buyer_id"), row.getString("winner_hold_no"),
                row.getBigDecimal("winner_hold_amount"), row.getBigDecimal("final_price"),
                row.getBigDecimal("captured_deposit_amount"), row.getBigDecimal("payable_amount"),
                row.getString("status"), row.getLong("version")), orderId);
        if (rows.size() != 1) {
            throw new DepositResultConflictException("trade order does not exist");
        }
        return rows.getFirst();
    }

    private static void requireCapture(WalletHoldSettledEvent result) {
        if (result.settlementType() != DepositSettlementType.CAPTURE
                || result.holdStatus() != WalletHoldSettlementStatus.CAPTURED
                || result.orderId() == null) {
            throw new DepositResultConflictException("trade accepts only a captured winner deposit result");
        }
    }

    private static void validateSnapshot(OrderRow order, WalletHoldSettledEvent result) {
        if (order.id() != result.orderId()
                || order.auctionId() != result.auctionId()
                || order.buyerId() != result.userId()
                || !order.winnerHoldNo().equals(result.holdNo())
                || order.winnerHoldAmount().compareTo(result.holdAmount()) != 0
                || order.finalPrice().compareTo(result.captureTargetAmount()) != 0) {
            throw new DepositResultConflictException("wallet settlement does not match the order snapshot");
        }
    }

    private static void validateExistingResult(
            OrderRow order, BigDecimal capturedAmount, BigDecimal payableAmount
    ) {
        if (order.capturedDepositAmount() == null || order.payableAmount() == null
                || order.capturedDepositAmount().compareTo(capturedAmount) != 0
                || order.payableAmount().compareTo(payableAmount) != 0) {
            throw new DepositResultConflictException("order already contains a different deposit result");
        }
    }

    private static void requireSingleChange(int changed) {
        if (changed != 1) {
            throw new DepositResultConflictException("trade order changed concurrently");
        }
    }

    private record OrderRow(
            long id, String orderNo, long auctionId, long sellerId, long buyerId,
            String winnerHoldNo, BigDecimal winnerHoldAmount, BigDecimal finalPrice,
            BigDecimal capturedDepositAmount, BigDecimal payableAmount, String status, long version
    ) { }

    public static final class DepositResultConflictException extends RuntimeException {
        public DepositResultConflictException(String message) {
            super(message);
        }
    }
}

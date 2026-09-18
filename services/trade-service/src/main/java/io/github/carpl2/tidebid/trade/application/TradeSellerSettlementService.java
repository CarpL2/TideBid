package io.github.carpl2.tidebid.trade.application;

import io.github.carpl2.tidebid.contracts.SellerCreditReason;
import io.github.carpl2.tidebid.contracts.SellerCreditedEvent;
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
public class TradeSellerSettlementService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TradeSellerSettlementService(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
    }

    @Transactional
    public void apply(SellerCreditedEvent result) {
        OrderRow order = lockOrder(result.orderId());
        validateSnapshot(order, result);
        if ("COMPLETED".equals(order.settlementStatus())) {
            if (!result.creditedAt().equals(order.creditedAt())) {
                throw new SellerSettlementConflictException(
                        "seller credit completion was replayed with a different timestamp");
            }
            return;
        }
        if (!"PENDING".equals(order.settlementStatus())) {
            throw new SellerSettlementConflictException("trade order is not awaiting seller credit");
        }

        Instant consumedAt = clock.instant();
        Instant updatedAt = consumedAt.isBefore(result.creditedAt())
                ? result.creditedAt()
                : consumedAt;
        int changed = jdbc.update("""
                UPDATE trade_order
                SET seller_settlement_status = 'COMPLETED', seller_credited_at = ?,
                    version = version + 1, updated_at = ?
                WHERE id = ? AND seller_settlement_status = 'PENDING' AND version = ?
                """, Timestamp.from(result.creditedAt()), Timestamp.from(updatedAt),
                order.id(), order.version());
        if (changed != 1) {
            throw new SellerSettlementConflictException("trade order changed concurrently");
        }
    }

    private OrderRow lockOrder(long orderId) {
        List<OrderRow> rows = jdbc.query("""
                SELECT id, order_no, auction_id, seller_id, status, seller_settlement_status,
                       seller_credit_no, seller_receivable_amount, seller_credited_at, version
                FROM trade_order WHERE id = ? FOR UPDATE
                """, (row, number) -> new OrderRow(
                row.getLong("id"), row.getString("order_no"), row.getLong("auction_id"),
                row.getLong("seller_id"), row.getString("status"),
                row.getString("seller_settlement_status"), row.getString("seller_credit_no"),
                row.getBigDecimal("seller_receivable_amount"),
                row.getTimestamp("seller_credited_at") == null
                        ? null : row.getTimestamp("seller_credited_at").toInstant(),
                row.getLong("version")), orderId);
        if (rows.size() != 1) {
            throw new SellerSettlementConflictException("trade order does not exist");
        }
        return rows.getFirst();
    }

    private static void validateSnapshot(OrderRow order, SellerCreditedEvent result) {
        SellerCreditReason expectedReason = switch (order.status()) {
            case "PAID" -> SellerCreditReason.SALE_PROCEEDS;
            case "PAYMENT_TIMEOUT" -> SellerCreditReason.DEFAULT_COMPENSATION;
            default -> throw new SellerSettlementConflictException(
                    "seller credit cannot complete a non-terminal trade order");
        };
        if (order.id() != result.orderId()
                || !order.orderNo().equals(result.orderNo())
                || order.auctionId() != result.auctionId()
                || order.sellerId() != result.sellerId()
                || !order.creditNo().equals(result.creditNo())
                || order.receivableAmount().compareTo(result.creditedAmount()) != 0
                || expectedReason != result.creditReason()) {
            throw new SellerSettlementConflictException(
                    "seller credit result does not match the trade order snapshot");
        }
    }

    private record OrderRow(
            long id,
            String orderNo,
            long auctionId,
            long sellerId,
            String status,
            String settlementStatus,
            String creditNo,
            BigDecimal receivableAmount,
            Instant creditedAt,
            long version
    ) { }

    public static final class SellerSettlementConflictException extends RuntimeException {
        public SellerSettlementConflictException(String message) {
            super(message);
        }
    }
}

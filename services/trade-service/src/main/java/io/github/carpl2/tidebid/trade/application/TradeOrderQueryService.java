package io.github.carpl2.tidebid.trade.application;

import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.trade.domain.TradeErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@Profile({"local-db", "nacos"})
public class TradeOrderQueryService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TradeOrderQueryService(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
    }

    public OrderPage findMine(long buyerId, int page, int size) {
        requireUserId(buyerId);
        validatePage(page, size);
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade_order WHERE buyer_id = ?", Long.class, buyerId);
        if (total == 0) {
            return new OrderPage(page, size, 0, 0, List.of());
        }
        Instant now = clock.instant();
        List<TradeOrderSnapshot> items = jdbc.query("""
                SELECT id, order_no, auction_id, item_id, seller_id, buyer_id, item_title,
                       final_price, captured_deposit_amount, payable_amount, status,
                       payment_deadline, paid_at, timed_out_at, seller_settlement_status,
                       seller_receivable_amount, seller_credited_at, auction_closed_at,
                       created_at, updated_at
                FROM trade_order
                WHERE buyer_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, (row, number) -> map(row, now), buyerId, size, offset(page, size));
        return new OrderPage(page, size, total, totalPages(total, size), items);
    }

    public OrderPage findSales(long sellerId, int page, int size) {
        requireUserId(sellerId);
        validatePage(page, size);
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade_order WHERE seller_id = ?", Long.class, sellerId);
        if (total == 0) {
            return new OrderPage(page, size, 0, 0, List.of());
        }
        Instant now = clock.instant();
        List<TradeOrderSnapshot> items = jdbc.query("""
                SELECT id, order_no, auction_id, item_id, seller_id, buyer_id, item_title,
                       final_price, captured_deposit_amount, payable_amount, status,
                       payment_deadline, paid_at, timed_out_at, seller_settlement_status,
                       seller_receivable_amount, seller_credited_at, auction_closed_at,
                       created_at, updated_at
                FROM trade_order
                WHERE seller_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, (row, number) -> map(row, now), sellerId, size, offset(page, size));
        return new OrderPage(page, size, total, totalPages(total, size), items);
    }

    public TradeOrderSnapshot findAccessible(long userId, long orderId) {
        requireUserId(userId);
        if (orderId <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "orderId must be a positive integer");
        }
        Instant now = clock.instant();
        List<TradeOrderSnapshot> rows = jdbc.query("""
                SELECT id, order_no, auction_id, item_id, seller_id, buyer_id, item_title,
                       final_price, captured_deposit_amount, payable_amount, status,
                       payment_deadline, paid_at, timed_out_at, seller_settlement_status,
                       seller_receivable_amount, seller_credited_at, auction_closed_at,
                       created_at, updated_at
                FROM trade_order WHERE id = ?
                """, (row, number) -> map(row, now), orderId);
        if (rows.isEmpty()) {
            throw new BusinessException(TradeErrorCode.ORDER_NOT_FOUND);
        }
        TradeOrderSnapshot order = rows.getFirst();
        if (order.buyerId() != userId && order.sellerId() != userId) {
            throw new BusinessException(TradeErrorCode.ORDER_FORBIDDEN);
        }
        return order;
    }

    private static TradeOrderSnapshot map(ResultSet row, Instant now) throws SQLException {
        Instant deadline = instant(row, "payment_deadline");
        String status = row.getString("status");
        return new TradeOrderSnapshot(
                row.getLong("id"), row.getString("order_no"), row.getLong("auction_id"),
                row.getLong("item_id"), row.getLong("seller_id"), row.getLong("buyer_id"),
                row.getString("item_title"), row.getBigDecimal("final_price"),
                row.getBigDecimal("captured_deposit_amount"), row.getBigDecimal("payable_amount"),
                status, deadline, instant(row, "paid_at"), instant(row, "timed_out_at"),
                row.getString("seller_settlement_status"), row.getBigDecimal("seller_receivable_amount"),
                instant(row, "seller_credited_at"), instant(row, "auction_closed_at"),
                instant(row, "created_at"), instant(row, "updated_at"),
                "PENDING_PAYMENT".equals(status) && deadline != null && now.isBefore(deadline));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "page must be positive and size must be 1 to 100");
        }
    }

    private static long offset(int page, int size) {
        return Math.multiplyExact((long) page - 1L, size);
    }

    private static long totalPages(long total, int size) {
        return total == 0 ? 0 : ((total - 1) / size) + 1;
    }

    private static void requireUserId(long userId) {
        if (userId <= 0) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
    }

    public record OrderPage(
            int page,
            int size,
            long total,
            long totalPages,
            List<TradeOrderSnapshot> items
    ) {
        public OrderPage {
            items = List.copyOf(items);
        }
    }
}

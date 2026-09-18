package io.github.carpl2.tidebid.trade.infrastructure.persistence;

import io.github.carpl2.tidebid.trade.application.port.TradeOrderRepository;
import io.github.carpl2.tidebid.trade.domain.SellerSettlementStatus;
import io.github.carpl2.tidebid.trade.domain.TradeOrder;
import io.github.carpl2.tidebid.trade.domain.TradeOrderStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@Profile({"local-db", "nacos"})
public class JdbcTradeOrderRepository implements TradeOrderRepository {

    private final JdbcTemplate jdbc;

    public JdbcTradeOrderRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<TradeOrder> findByAuctionId(long auctionId) {
        return findByAuctionId(auctionId, false);
    }

    @Override
    public Optional<TradeOrder> findByAuctionIdForUpdate(long auctionId) {
        return findByAuctionId(auctionId, true);
    }

    private Optional<TradeOrder> findByAuctionId(long auctionId, boolean lockCurrentRow) {
        String lockingClause = lockCurrentRow ? " FOR UPDATE" : "";
        List<TradeOrder> rows = jdbc.query("""
                SELECT id, order_no, auction_id, item_id, winning_bid_id, seller_id, buyer_id,
                       item_title, winner_hold_no, winner_hold_amount, final_price, status, seller_settlement_status,
                       version, auction_closed_at, created_at, updated_at
                FROM trade_order WHERE auction_id = ?
                """ + lockingClause, (row, number) -> new TradeOrder(
                row.getLong("id"),
                row.getString("order_no"),
                row.getLong("auction_id"),
                row.getLong("item_id"),
                row.getLong("winning_bid_id"),
                row.getLong("seller_id"),
                row.getLong("buyer_id"),
                row.getString("item_title"),
                row.getString("winner_hold_no"),
                row.getBigDecimal("winner_hold_amount"),
                row.getBigDecimal("final_price"),
                TradeOrderStatus.valueOf(row.getString("status")),
                SellerSettlementStatus.valueOf(row.getString("seller_settlement_status")),
                row.getLong("version"),
                row.getTimestamp("auction_closed_at").toInstant(),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant()), auctionId);
        if (rows.size() > 1) {
            throw new IllegalStateException("auction has more than one trade order");
        }
        return rows.stream().findFirst();
    }

    @Override
    public boolean insertIfAbsent(TradeOrder order) {
        jdbc.update("""
                INSERT INTO trade_order (
                    id, order_no, auction_id, item_id, winning_bid_id, seller_id, buyer_id,
                    item_title, winner_hold_no, winner_hold_amount, final_price, status, seller_settlement_status,
                    version, auction_closed_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """,
                order.id(), order.orderNo(), order.auctionId(), order.itemId(), order.winningBidId(),
                order.sellerId(), order.buyerId(), order.itemTitle(), order.winnerHoldNo(),
                order.winnerHoldAmount(), order.finalPrice(),
                order.status().name(), order.sellerSettlementStatus().name(), order.version(),
                Timestamp.from(order.auctionClosedAt()), Timestamp.from(order.createdAt()),
                Timestamp.from(order.updatedAt()));
        // A locking read is a current read under MySQL REPEATABLE READ, so a transaction that
        // lost a concurrent unique-key race can see the committed winner instead of its old snapshot.
        return findByAuctionId(order.auctionId(), true)
                .map(persisted -> persisted.id() == order.id())
                .orElseThrow(() -> new IllegalStateException("inserted order is not visible"));
    }
}

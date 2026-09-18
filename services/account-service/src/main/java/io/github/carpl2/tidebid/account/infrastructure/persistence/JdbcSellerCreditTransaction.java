package io.github.carpl2.tidebid.account.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.carpl2.tidebid.account.application.port.SellerCreditTransaction;
import io.github.carpl2.tidebid.account.domain.WalletLedgerType;
import io.github.carpl2.tidebid.account.infrastructure.messaging.AccountOutboxEventFactory;
import io.github.carpl2.tidebid.account.infrastructure.messaging.JdbcAccountOutboxRepository;
import io.github.carpl2.tidebid.contracts.SellerCreditRequestedEvent;
import io.github.carpl2.tidebid.contracts.SellerCreditReason;
import io.github.carpl2.tidebid.contracts.SellerCreditedEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@Profile({"local-db", "nacos"})
public class JdbcSellerCreditTransaction implements SellerCreditTransaction {

    private final JdbcTemplate jdbc;
    private final JdbcAccountOutboxRepository outbox;
    private final AccountOutboxEventFactory events;
    private final Clock clock;

    public JdbcSellerCreditTransaction(
            DataSource dataSource,
            JdbcAccountOutboxRepository outbox,
            AccountOutboxEventFactory events,
            Clock clock
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.outbox = outbox;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SellerCreditedEvent credit(
            UUID sourceEventId,
            String traceId,
            SellerCreditRequestedEvent request
    ) {
        WalletRow wallet = lockWallet(request.sellerId());
        CreditRow existing = findCredit(request.creditNo());
        if (existing != null) {
            validateExisting(existing, request);
            return toEvent(existing, request.orderNo());
        }

        Instant now = clock.instant();
        BigDecimal availableAfter = wallet.available().add(request.creditAmount());
        int walletChanged = jdbc.update("""
                UPDATE wallet_account
                SET available_balance = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND version = ?
                """, availableAfter, Timestamp.from(now), wallet.id(), wallet.version());
        if (walletChanged != 1) {
            throw new SellerCreditConflictException("seller wallet changed concurrently");
        }

        try {
            jdbc.update("""
                    INSERT INTO wallet_credit (
                        id, credit_no, seller_id, order_id, auction_id, credit_reason,
                        amount, completed_at, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, IdWorker.getId(), request.creditNo(), request.sellerId(), request.orderId(),
                    request.auctionId(), request.creditReason().name(), request.creditAmount(),
                    Timestamp.from(now), Timestamp.from(now));
            jdbc.update("""
                    INSERT INTO wallet_ledger (
                        id, wallet_id, business_no, ledger_type, available_delta, frozen_delta,
                        available_balance_after, frozen_balance_after, created_at
                    ) VALUES (?, ?, ?, ?, ?, 0.00, ?, ?, ?)
                    """, IdWorker.getId(), wallet.id(), request.creditNo(), WalletLedgerType.SELLER_CREDIT.name(),
                    request.creditAmount(), availableAfter, wallet.frozen(), Timestamp.from(now));
        } catch (DuplicateKeyException exception) {
            throw new SellerCreditConflictException(
                    "seller credit identity conflicts with another durable credit", exception);
        }

        SellerCreditedEvent result = new SellerCreditedEvent(
                request.creditReason(), request.creditNo(), request.orderId(), request.orderNo(),
                request.auctionId(), request.sellerId(), request.creditAmount(), now);
        outbox.enqueue(events.sellerCredited(result, traceId), now);
        return result;
    }

    private WalletRow lockWallet(long sellerId) {
        List<WalletRow> rows = jdbc.query("""
                SELECT id, available_balance, frozen_balance, version
                FROM wallet_account WHERE user_id = ? FOR UPDATE
                """, (row, number) -> new WalletRow(
                row.getLong("id"), row.getBigDecimal("available_balance"),
                row.getBigDecimal("frozen_balance"), row.getLong("version")), sellerId);
        if (rows.size() != 1) {
            throw new SellerCreditConflictException("seller wallet does not exist");
        }
        return rows.getFirst();
    }

    private CreditRow findCredit(String creditNo) {
        List<CreditRow> rows = jdbc.query("""
                SELECT credit_no, seller_id, order_id, auction_id, credit_reason, amount, completed_at
                FROM wallet_credit WHERE credit_no = ?
                """, (row, number) -> new CreditRow(
                row.getString("credit_no"), row.getLong("seller_id"), row.getLong("order_id"),
                row.getLong("auction_id"), row.getString("credit_reason"), row.getBigDecimal("amount"),
                row.getTimestamp("completed_at").toInstant()), creditNo);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void validateExisting(CreditRow existing, SellerCreditRequestedEvent request) {
        if (existing.sellerId() != request.sellerId()
                || existing.orderId() != request.orderId()
                || existing.auctionId() != request.auctionId()
                || !existing.creditReason().equals(request.creditReason().name())
                || existing.amount().compareTo(request.creditAmount()) != 0
                || !loadCreditedOrderNo(existing.creditNo()).equals(request.orderNo())) {
            throw new SellerCreditConflictException("seller credit was replayed with different intent");
        }
    }

    private String loadCreditedOrderNo(String creditNo) {
        String eventId = AccountOutboxEventFactory.deterministicSellerCreditedEventId(creditNo).toString();
        List<String> orderNumbers = jdbc.query("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.orderNo'))
                FROM account_outbox WHERE event_id = ? AND event_type = ?
                """, (row, number) -> row.getString(1), eventId, SellerCreditedEvent.EVENT_TYPE);
        if (orderNumbers.size() != 1 || orderNumbers.getFirst() == null) {
            throw new SellerCreditConflictException(
                    "stored seller credit is missing its durable result event");
        }
        return orderNumbers.getFirst();
    }

    private static SellerCreditedEvent toEvent(CreditRow existing, String orderNo) {
        return new SellerCreditedEvent(
                SellerCreditReason.valueOf(existing.creditReason()), existing.creditNo(),
                existing.orderId(), orderNo, existing.auctionId(), existing.sellerId(),
                existing.amount(), existing.completedAt());
    }

    private record WalletRow(long id, BigDecimal available, BigDecimal frozen, long version) { }
    private record CreditRow(String creditNo, long sellerId, long orderId, long auctionId,
                             String creditReason, BigDecimal amount, Instant completedAt) { }

    public static final class SellerCreditConflictException extends RuntimeException {
        public SellerCreditConflictException(String message) {
            super(message);
        }

        public SellerCreditConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package io.github.carpl2.tidebid.account.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.carpl2.tidebid.account.application.port.DuplicateWalletDebitException;
import io.github.carpl2.tidebid.account.application.port.WalletAccountDisabledException;
import io.github.carpl2.tidebid.account.application.port.WalletAccountNotFoundException;
import io.github.carpl2.tidebid.account.application.port.WalletDebitRepository;
import io.github.carpl2.tidebid.account.application.port.WalletDebitTransaction;
import io.github.carpl2.tidebid.account.application.port.WalletNotFoundException;
import io.github.carpl2.tidebid.account.domain.WalletDebit;
import io.github.carpl2.tidebid.account.domain.WalletDebitStatus;
import io.github.carpl2.tidebid.account.domain.WalletLedgerType;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Component
@Profile({"local-db", "nacos"})
public class JdbcWalletDebitTransaction implements WalletDebitTransaction {

    private final JdbcTemplate jdbc;
    private final WalletDebitRepository repository;
    private final Clock clock;

    public JdbcWalletDebitTransaction(DataSource dataSource, WalletDebitRepository repository, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public WalletDebit create(DebitData data) {
        AccountRow account = lockAccount(data.userId());
        if (!"ACTIVE".equals(account.status())) {
            throw new WalletAccountDisabledException();
        }
        WalletRow wallet = lockWallet(data.userId());

        WalletDebit existing = repository.findByPaymentNo(data.paymentNo()).orElse(null);
        if (existing != null) {
            return existing;
        }

        Instant now = clock.instant();
        if (wallet.available().compareTo(data.amount()) < 0) {
            return insertResult(data, WalletDebitStatus.REJECTED,
                    WalletDebit.INSUFFICIENT_BALANCE, now);
        }

        int changed = jdbc.update("""
                UPDATE wallet_account
                SET available_balance = available_balance - ?, version = version + 1, updated_at = ?
                WHERE id = ? AND version = ? AND available_balance >= ?
                """, data.amount(), Timestamp.from(now), wallet.id(), wallet.version(), data.amount());
        if (changed != 1) {
            throw new IllegalStateException("Wallet changed while holding its database lock");
        }

        WalletDebit result = insertResult(data, WalletDebitStatus.SUCCEEDED, null, now);
        WalletRow updated = loadWallet(data.userId());
        int ledgerInserted = jdbc.update("""
                INSERT INTO wallet_ledger (
                    id, wallet_id, business_no, ledger_type, available_delta, frozen_delta,
                    available_balance_after, frozen_balance_after, created_at
                ) VALUES (?, ?, ?, ?, ?, 0.00, ?, ?, ?)
                """, IdWorker.getId(), updated.id(), data.paymentNo(), WalletLedgerType.ORDER_PAYMENT.name(),
                data.amount().negate(), updated.available(), updated.frozen(), Timestamp.from(now));
        if (ledgerInserted != 1) {
            throw new IllegalStateException("Expected one inserted wallet debit ledger");
        }
        return result;
    }

    private WalletDebit insertResult(
            DebitData data,
            WalletDebitStatus status,
            String failureCode,
            Instant now
    ) {
        long id = IdWorker.getId();
        try {
            int inserted = jdbc.update("""
                    INSERT INTO wallet_debit (
                        id, payment_no, user_id, order_id, amount, status, failure_code,
                        decided_at, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, data.paymentNo(), data.userId(), data.orderId(), data.amount(), status.name(),
                    failureCode, Timestamp.from(now), Timestamp.from(now));
            if (inserted != 1) {
                throw new IllegalStateException("Expected one inserted wallet debit result");
            }
        } catch (DuplicateKeyException exception) {
            throw new DuplicateWalletDebitException(exception);
        }
        return new WalletDebit(id, data.paymentNo(), data.userId(), data.orderId(), data.amount(),
                status, failureCode, now, now);
    }

    private AccountRow lockAccount(long userId) {
        List<AccountRow> rows = jdbc.query("""
                SELECT id, status FROM user_account WHERE id = ? FOR UPDATE
                """, (row, number) -> new AccountRow(row.getLong("id"), row.getString("status")), userId);
        if (rows.size() != 1) {
            throw new WalletAccountNotFoundException();
        }
        return rows.getFirst();
    }

    private WalletRow lockWallet(long userId) {
        List<WalletRow> rows = jdbc.query("""
                SELECT id, available_balance, frozen_balance, version
                FROM wallet_account WHERE user_id = ? FOR UPDATE
                """, (row, number) -> walletRow(row), userId);
        if (rows.size() != 1) {
            throw new WalletNotFoundException();
        }
        return rows.getFirst();
    }

    private WalletRow loadWallet(long userId) {
        List<WalletRow> rows = jdbc.query("""
                SELECT id, available_balance, frozen_balance, version
                FROM wallet_account WHERE user_id = ?
                """, (row, number) -> walletRow(row), userId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Updated wallet could not be reloaded");
        }
        return rows.getFirst();
    }

    private static WalletRow walletRow(java.sql.ResultSet row) throws java.sql.SQLException {
        return new WalletRow(row.getLong("id"), row.getBigDecimal("available_balance"),
                row.getBigDecimal("frozen_balance"), row.getLong("version"));
    }

    private record AccountRow(long id, String status) {
    }

    private record WalletRow(long id, java.math.BigDecimal available,
                             java.math.BigDecimal frozen, long version) {
    }
}

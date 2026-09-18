package io.github.carpl2.tidebid.account.infrastructure.persistence;

import io.github.carpl2.tidebid.account.application.port.WalletDebitRepository;
import io.github.carpl2.tidebid.account.domain.WalletDebit;
import io.github.carpl2.tidebid.account.domain.WalletDebitStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

@Repository
@Profile({"local-db", "nacos"})
public class JdbcWalletDebitRepository implements WalletDebitRepository {

    private final JdbcTemplate jdbc;

    public JdbcWalletDebitRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<WalletDebit> findByPaymentNo(String paymentNo) {
        List<WalletDebit> rows = jdbc.query("""
                SELECT id, payment_no, user_id, order_id, amount, status, failure_code,
                       decided_at, created_at
                FROM wallet_debit
                WHERE payment_no = ?
                """, (row, number) -> new WalletDebit(
                row.getLong("id"),
                row.getString("payment_no"),
                row.getLong("user_id"),
                row.getLong("order_id"),
                row.getBigDecimal("amount"),
                WalletDebitStatus.valueOf(row.getString("status")),
                row.getString("failure_code"),
                row.getTimestamp("decided_at").toInstant(),
                row.getTimestamp("created_at").toInstant()
        ), paymentNo);
        return rows.stream().findFirst();
    }
}

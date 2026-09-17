package io.github.carpl2.tidebid.trade;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TIDEBID_MYSQL_ROOT_PASSWORD", matches = ".+")
class TradeMigrationIsolationTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "flyway_schema_history", "payment_attempt", "trade_inbox", "trade_order", "trade_outbox"
    );

    @Test
    void diagnosesNonTransactionalDdlFailureAndRecoversWithFreshSchema() throws Exception {
        DatabaseTarget target = databaseTarget();
        String schema = "tidebid_trade_verify_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        String schemaUrl = jdbcUrl(target.host(), target.port(), schema);

        try (Connection admin = DriverManager.getConnection(target.serverUrl(), "root", target.rootPassword());
             Statement statement = admin.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + schema
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                Flyway failingFlyway = Flyway.configure()
                        .dataSource(schemaUrl, "root", target.rootPassword())
                        .locations("classpath:db/failure-migration")
                        .defaultSchema(schema)
                        .cleanDisabled(true)
                        .validateOnMigrate(true)
                        .baselineOnMigrate(false)
                        .load();

                assertThatThrownBy(failingFlyway::migrate)
                        .isInstanceOf(FlywayException.class)
                        .hasMessageContaining("V1__intentional_failure.sql");
                assertThat(readTables(schemaUrl, target.rootPassword())).contains("migration_probe");

                statement.executeUpdate("DROP DATABASE `" + schema + "`");
                statement.executeUpdate("CREATE DATABASE `" + schema
                        + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");

                Flyway recovered = Flyway.configure()
                        .dataSource(schemaUrl, "root", target.rootPassword())
                        .locations("classpath:db/migration")
                        .defaultSchema(schema)
                        .cleanDisabled(true)
                        .validateOnMigrate(true)
                        .baselineOnMigrate(false)
                        .load();
                assertThat(recovered.migrate().migrationsExecuted).isOne();
                assertThat(readTables(schemaUrl, target.rootPassword())).containsExactlyElementsOf(EXPECTED_TABLES);
            } finally {
                statement.executeUpdate("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    @Test
    void migratesEmptySchemaRepeatablyAndEnforcesCoreConstraints() throws Exception {
        DatabaseTarget target = databaseTarget();
        String schema = "tidebid_trade_verify_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        String schemaUrl = jdbcUrl(target.host(), target.port(), schema);

        try (Connection admin = DriverManager.getConnection(target.serverUrl(), "root", target.rootPassword());
             Statement adminStatement = admin.createStatement()) {
            adminStatement.executeUpdate("CREATE DATABASE `" + schema
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                Flyway flyway = Flyway.configure()
                        .dataSource(schemaUrl, "root", target.rootPassword())
                        .locations("classpath:db/migration")
                        .defaultSchema(schema)
                        .cleanDisabled(true)
                        .validateOnMigrate(true)
                        .baselineOnMigrate(false)
                        .load();
                assertThat(flyway.migrate().migrationsExecuted).isOne();
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                assertThat(readTables(schemaUrl, target.rootPassword())).containsExactlyElementsOf(EXPECTED_TABLES);

                try (Connection connection = DriverManager.getConnection(schemaUrl, "root", target.rootPassword());
                     Statement statement = connection.createStatement()) {
                    assertThat(statement.executeUpdate("""
                            INSERT INTO trade_order (
                                id, order_no, auction_id, item_id, winning_bid_id, seller_id, buyer_id,
                                item_title, winner_hold_no, final_price, status, seller_settlement_status,
                                version, auction_closed_at, created_at, updated_at
                            ) VALUES (
                                101, 'ORDER:101', 201, 301, 401, 501, 502, 'Migration item',
                                'HOLD:101', 500.00, 'PENDING_DEPOSIT', 'NOT_REQUIRED', 0,
                                '2026-09-17 01:00:00.123456', '2026-09-17 01:00:01.123456',
                                '2026-09-17 01:00:01.123456'
                            )
                            """)).isOne();
                    assertThat(statement.executeUpdate("""
                            INSERT INTO payment_attempt (
                                id, payment_no, order_id, buyer_id, request_id, amount, status,
                                recovery_count, created_at, updated_at
                            ) VALUES (
                                601, 'PAY:601', 101, 502, 'request-00000001', 400.00,
                                'PROCESSING', 0, '2026-09-17 01:10:00.000001',
                                '2026-09-17 01:10:00.000001'
                            )
                            """)).isOne();
                    assertThatThrownBy(() -> statement.executeUpdate("""
                            INSERT INTO trade_order (
                                id, order_no, auction_id, item_id, winning_bid_id, seller_id, buyer_id,
                                item_title, winner_hold_no, final_price, status, seller_settlement_status,
                                version, auction_closed_at, created_at, updated_at
                            ) VALUES (
                                102, 'ORDER:102', 201, 302, 402, 503, 504, 'Duplicate auction',
                                'HOLD:102', 100.00, 'PENDING_DEPOSIT', 'NOT_REQUIRED', 0,
                                '2026-09-17 01:00:00.000001', '2026-09-17 01:00:01.000001',
                                '2026-09-17 01:00:01.000001'
                            )
                            """)).isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE trade_order
                            SET status = 'PENDING_PAYMENT', captured_deposit_amount = 100.00,
                                payable_amount = 399.99, payment_deadline = '2026-09-17 02:00:00.000001'
                            WHERE id = 101
                            """)).isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> statement.executeUpdate("""
                            INSERT INTO payment_attempt (
                                id, payment_no, order_id, buyer_id, request_id, amount, status,
                                recovery_count, created_at, updated_at
                            ) VALUES (
                                602, 'PAY:602', 101, 502, 'request-00000001', 400.00,
                                'PROCESSING', 0, '2026-09-17 01:11:00.000001',
                                '2026-09-17 01:11:00.000001'
                            )
                            """)).isInstanceOf(SQLException.class);
                }

                assertThat(readIndexes(schemaUrl, target.rootPassword(), "trade_order"))
                        .contains("uk_trade_order_order_no", "uk_trade_order_auction_id", "idx_trade_order_payment_deadline");
                assertThat(readIndexes(schemaUrl, target.rootPassword(), "payment_attempt"))
                        .contains("uk_payment_attempt_payment_no", "uk_payment_attempt_buyer_request", "idx_payment_attempt_recovery_scan");
                assertThat(readIndexes(schemaUrl, target.rootPassword(), "trade_outbox"))
                        .contains("uk_trade_outbox_event_id", "idx_trade_outbox_pending_scan");
            } finally {
                adminStatement.executeUpdate("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private static List<String> readTables(String url, String password) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, "root", password);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    private static List<String> readIndexes(String url, String password, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, "root", password);
             var statement = connection.prepareStatement("SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY INDEX_NAME")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) names.add(rows.getString(1));
            }
        }
        return names;
    }

    private static DatabaseTarget databaseTarget() {
        String host = environmentOrDefault("TIDEBID_MYSQL_HOST", "127.0.0.1");
        String port = environmentOrDefault("TIDEBID_MYSQL_PORT", "13306");
        return new DatabaseTarget(host, port, System.getenv("TIDEBID_MYSQL_ROOT_PASSWORD"), jdbcUrl(host, port, ""));
    }

    private static String jdbcUrl(String host, String port, String schema) {
        return "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record DatabaseTarget(String host, String port, String rootPassword, String serverUrl) { }
}

package io.github.carpl2.tidebid.auction;

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
class AuctionMigrationIsolationTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "auction_item",
            "auction_item_image",
            "auction_registration",
            "auction_review",
            "auction_session",
            "bid_record",
            "flyway_schema_history"
    );

    @Test
    void migratesAnIsolatedEmptySchemaAndIsRepeatable() throws Exception {
        String host = environmentOrDefault("TIDEBID_MYSQL_HOST", "127.0.0.1");
        String port = environmentOrDefault("TIDEBID_MYSQL_PORT", "13306");
        String rootPassword = System.getenv("TIDEBID_MYSQL_ROOT_PASSWORD");
        String schema = "tidebid_auction_verify_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        assertThat(schema).matches("tidebid_auction_verify_[0-9a-f]{12}");

        String serverUrl = jdbcUrl(host, port, "");
        String schemaUrl = jdbcUrl(host, port, schema);
        try (Connection admin = DriverManager.getConnection(serverUrl, "root", rootPassword);
             Statement statement = admin.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + schema
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                Flyway flyway = Flyway.configure()
                        .dataSource(schemaUrl, "root", rootPassword)
                        .locations("classpath:db/migration")
                        .defaultSchema(schema)
                        .cleanDisabled(true)
                        .validateOnMigrate(true)
                        .baselineOnMigrate(false)
                        .load();

                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                assertThat(readTables(schemaUrl, rootPassword)).containsExactlyElementsOf(EXPECTED_TABLES);
                assertThat(readColumns(schemaUrl, rootPassword, "auction_session"))
                        .contains("winner_id", "winning_bid_id", "final_price", "closed_at");
            } finally {
                statement.executeUpdate("DROP DATABASE `" + schema + "`");
            }
        }
    }

    @Test
    void diagnosesNonTransactionalDdlFailureAndRecoversWithFreshSchema() throws Exception {
        String host = environmentOrDefault("TIDEBID_MYSQL_HOST", "127.0.0.1");
        String port = environmentOrDefault("TIDEBID_MYSQL_PORT", "13306");
        String rootPassword = System.getenv("TIDEBID_MYSQL_ROOT_PASSWORD");
        String schema = "tidebid_auction_verify_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        assertThat(schema).matches("tidebid_auction_verify_[0-9a-f]{12}");

        String serverUrl = jdbcUrl(host, port, "");
        String schemaUrl = jdbcUrl(host, port, schema);
        try (Connection admin = DriverManager.getConnection(serverUrl, "root", rootPassword);
             Statement statement = admin.createStatement()) {
            createSchema(statement, schema);
            try {
                Flyway failingFlyway = Flyway.configure()
                        .dataSource(schemaUrl, "root", rootPassword)
                        .locations("classpath:db/failure-migration")
                        .defaultSchema(schema)
                        .cleanDisabled(true)
                        .validateOnMigrate(true)
                        .baselineOnMigrate(false)
                        .load();

                assertThatThrownBy(failingFlyway::migrate)
                        .isInstanceOf(FlywayException.class)
                        .hasMessageContaining("V1__intentional_failure.sql");
                assertThat(readTables(schemaUrl, rootPassword)).contains("migration_probe");

                statement.executeUpdate("DROP DATABASE `" + schema + "`");
                createSchema(statement, schema);

                Flyway recoveredFlyway = Flyway.configure()
                        .dataSource(schemaUrl, "root", rootPassword)
                        .locations("classpath:db/migration")
                        .defaultSchema(schema)
                        .cleanDisabled(true)
                        .validateOnMigrate(true)
                        .baselineOnMigrate(false)
                        .load();

                assertThat(recoveredFlyway.migrate().migrationsExecuted).isEqualTo(2);
                assertThat(readTables(schemaUrl, rootPassword)).containsExactlyElementsOf(EXPECTED_TABLES);
            } finally {
                statement.executeUpdate("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    @Test
    void upgradesExistingSessionsAndEnforcesTerminalSnapshots() throws Exception {
        String host = environmentOrDefault("TIDEBID_MYSQL_HOST", "127.0.0.1");
        String port = environmentOrDefault("TIDEBID_MYSQL_PORT", "13306");
        String rootPassword = System.getenv("TIDEBID_MYSQL_ROOT_PASSWORD");
        String schema = "tidebid_auction_verify_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        String serverUrl = jdbcUrl(host, port, "");
        String schemaUrl = jdbcUrl(host, port, schema);

        try (Connection admin = DriverManager.getConnection(serverUrl, "root", rootPassword);
             Statement adminStatement = admin.createStatement()) {
            createSchema(adminStatement, schema);
            try {
                Flyway versionOne = Flyway.configure()
                        .dataSource(schemaUrl, "root", rootPassword)
                        .locations("classpath:db/migration")
                        .defaultSchema(schema)
                        .target("1")
                        .cleanDisabled(true)
                        .load();
                assertThat(versionOne.migrate().migrationsExecuted).isEqualTo(1);

                try (Connection connection = DriverManager.getConnection(schemaUrl, "root", rootPassword);
                     Statement statement = connection.createStatement()) {
                    insertItem(statement, 101, 301);
                    insertSession(statement, 201, 101, 301, null, null, 0);
                    insertItem(statement, 102, 302);
                    insertSession(statement, 202, 102, 302, "150.00", 402L, 1);
                    statement.executeUpdate("""
                            INSERT INTO bid_record (
                                id, auction_id, bidder_id, request_id, amount, previous_price, sequence_no, created_at
                            ) VALUES (
                                402, 202, 402, 'closing-migration-bid', 150.00, NULL, 1,
                                '2026-09-16 12:30:00.000000'
                            )
                            """);
                }

                Flyway latest = Flyway.configure()
                        .dataSource(schemaUrl, "root", rootPassword)
                        .locations("classpath:db/migration")
                        .defaultSchema(schema)
                        .cleanDisabled(true)
                        .load();
                assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);

                try (Connection connection = DriverManager.getConnection(schemaUrl, "root", rootPassword);
                     Statement statement = connection.createStatement()) {
                    try (ResultSet existing = statement.executeQuery("""
                            SELECT status, winner_id, winning_bid_id, final_price, closed_at
                            FROM auction_session
                            WHERE id = 201
                            """)) {
                        assertThat(existing.next()).isTrue();
                        assertThat(existing.getString("status")).isEqualTo("AWAITING_CLOSE");
                        assertThat(existing.getObject("winner_id")).isNull();
                        assertThat(existing.getObject("winning_bid_id")).isNull();
                        assertThat(existing.getBigDecimal("final_price")).isNull();
                        assertThat(existing.getTimestamp("closed_at")).isNull();
                    }

                    assertThat(statement.executeUpdate("""
                            UPDATE auction_session
                            SET status = 'CLOSED_UNSOLD', closed_at = '2026-09-16 13:00:00.000000'
                            WHERE id = 201
                            """)).isOne();
                    assertThat(statement.executeUpdate("""
                            UPDATE auction_session
                            SET status = 'CLOSED_SOLD', winner_id = 402, winning_bid_id = 402,
                                final_price = 150.00, closed_at = '2026-09-16 13:00:00.000000'
                            WHERE id = 202
                            """)).isOne();

                    assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE auction_session SET final_price = 149.00 WHERE id = 202
                            """))
                            .isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE auction_session SET winner_id = 999 WHERE id = 201
                            """))
                            .isInstanceOf(SQLException.class);
                }
            } finally {
                adminStatement.executeUpdate("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private static void createSchema(Statement statement, String schema) throws Exception {
        statement.executeUpdate("CREATE DATABASE `" + schema
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
    }

    private static List<String> readTables(String schemaUrl, String rootPassword) throws Exception {
        List<String> tables = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(schemaUrl, "root", rootPassword);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME"
             )) {
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
        }
        return tables;
    }

    private static List<String> readColumns(
            String schemaUrl,
            String rootPassword,
            String tableName
    ) throws Exception {
        List<String> columns = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(schemaUrl, "root", rootPassword);
             var statement = connection.prepareStatement(
                     "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION"
             )) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
            }
        }
        return columns;
    }

    private static void insertItem(Statement statement, long itemId, long sellerId) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO auction_item (
                    id, seller_id, title, description, category, item_condition, review_status,
                    submission_version, version, submitted_at, approved_at, created_at, updated_at
                ) VALUES (
                    %d, %d, 'Migration item', 'Existing item retained across the closing migration',
                    'COLLECTIBLES', 'GOOD', 'APPROVED', 1, 1,
                    '2026-09-16 11:00:00.000000', '2026-09-16 11:10:00.000000',
                    '2026-09-16 10:00:00.000000', '2026-09-16 11:10:00.000000'
                )
                """.formatted(itemId, sellerId));
    }

    private static void insertSession(
            Statement statement,
            long auctionId,
            long itemId,
            long sellerId,
            String currentPrice,
            Long currentBidderId,
            long bidCount
    ) throws SQLException {
        String nullablePrice = currentPrice == null ? "NULL" : currentPrice;
        String nullableBidder = currentBidderId == null ? "NULL" : currentBidderId.toString();
        statement.executeUpdate("""
                INSERT INTO auction_session (
                    id, item_id, seller_id, start_price, bid_increment, deposit_amount,
                    current_price, current_bidder_id, bid_count, start_at, end_at, status,
                    version, created_at, updated_at
                ) VALUES (
                    %d, %d, %d, 100.00, 10.00, 50.00,
                    %s, %s, %d, '2026-09-16 12:00:00.000000', '2026-09-16 13:00:00.000000',
                    'AWAITING_CLOSE', 3, '2026-09-16 10:00:00.000000', '2026-09-16 13:00:00.000000'
                )
                """.formatted(
                auctionId, itemId, sellerId, nullablePrice, nullableBidder, bidCount
        ));
    }

    private static String jdbcUrl(String host, String port, String schema) {
        return "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC"
                + "&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}

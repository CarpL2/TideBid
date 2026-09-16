package io.github.carpl2.tidebid.account;

import org.flywaydb.core.Flyway;
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
class AccountMigrationIsolationTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "flyway_schema_history",
            "user_account",
            "user_role",
            "wallet_account",
            "wallet_hold",
            "wallet_ledger"
    );

    @Test
    void migratesAnIsolatedEmptySchemaAndIsRepeatable() throws Exception {
        DatabaseTarget target = databaseTarget();
        String schema = randomSchema();
        String schemaUrl = jdbcUrl(target.host(), target.port(), schema);

        try (Connection admin = DriverManager.getConnection(target.serverUrl(), "root", target.rootPassword());
             Statement statement = admin.createStatement()) {
            createSchema(statement, schema);
            try {
                Flyway flyway = flyway(schemaUrl, target.rootPassword(), schema, null);

                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                assertThat(readTables(schemaUrl, target.rootPassword()))
                        .containsExactlyElementsOf(EXPECTED_TABLES);
                assertThat(readColumns(schemaUrl, target.rootPassword(), "wallet_hold"))
                        .contains(
                                "captured_amount",
                                "released_amount",
                                "settlement_event_id",
                                "settled_at"
                        );
            } finally {
                statement.executeUpdate("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    @Test
    void upgradesExistingHeldFundsAndEnforcesSettlementSnapshots() throws Exception {
        DatabaseTarget target = databaseTarget();
        String schema = randomSchema();
        String schemaUrl = jdbcUrl(target.host(), target.port(), schema);

        try (Connection admin = DriverManager.getConnection(target.serverUrl(), "root", target.rootPassword());
             Statement adminStatement = admin.createStatement()) {
            createSchema(adminStatement, schema);
            try {
                Flyway versionTwo = flyway(schemaUrl, target.rootPassword(), schema, "2");
                assertThat(versionTwo.migrate().migrationsExecuted).isEqualTo(2);

                try (Connection connection = DriverManager.getConnection(schemaUrl, "root", target.rootPassword());
                     Statement statement = connection.createStatement()) {
                    insertAccount(statement);
                    insertHeld(statement, 201, "REGISTRATION:201", "500.00");
                    insertHeld(statement, 202, "REGISTRATION:202", "500.00");
                }

                Flyway latest = flyway(schemaUrl, target.rootPassword(), schema, null);
                assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);

                assertThat(readIndexes(schemaUrl, target.rootPassword(), "wallet_hold"))
                        .contains(
                                "PRIMARY",
                                "uk_wallet_hold_hold_no",
                                "uk_wallet_hold_settlement_event_id",
                                "idx_wallet_hold_user_status_created"
                        );

                try (Connection connection = DriverManager.getConnection(schemaUrl, "root", target.rootPassword());
                     Statement statement = connection.createStatement()) {
                    assertExistingHoldRemainsHeld(statement);

                    assertThat(statement.executeUpdate("""
                            UPDATE wallet_hold
                            SET status = 'RELEASED', captured_amount = 0.00, released_amount = amount,
                                settlement_event_id = '01994cfd-e548-78e2-98f3-adb673d563a0',
                                settled_at = '2026-09-17 02:00:00.123456', version = version + 1,
                                updated_at = '2026-09-17 02:00:00.123456'
                            WHERE id = 201
                            """)).isOne();
                    assertThat(statement.executeUpdate("""
                            UPDATE wallet_hold
                            SET status = 'CAPTURED', captured_amount = 300.00, released_amount = 200.00,
                                settlement_event_id = '01994cfd-e548-78e2-98f3-adb673d563a1',
                                settled_at = '2026-09-17 02:00:00.654321', version = version + 1,
                                updated_at = '2026-09-17 02:00:00.654321'
                            WHERE id = 202
                            """)).isOne();

                    assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE wallet_hold SET captured_amount = 1.00 WHERE id = 201
                            """)).isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE wallet_hold SET released_amount = 199.99 WHERE id = 202
                            """)).isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE wallet_hold
                            SET settlement_event_id = 'not-a-uuid' WHERE id = 202
                            """)).isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> statement.executeUpdate("""
                            INSERT INTO wallet_hold (
                                id, hold_no, user_id, business_type, amount, captured_amount,
                                released_amount, status, settlement_event_id, settled_at,
                                version, created_at, updated_at
                            ) VALUES (
                                203, 'REGISTRATION:203', 101, 'AUCTION_DEPOSIT', 100.00, 0.00,
                                100.00, 'RELEASED', '01994cfd-e548-78e2-98f3-adb673d563a0',
                                '2026-09-17 02:00:00.000000', 1,
                                '2026-09-17 01:00:00.000000', '2026-09-17 02:00:00.000000'
                            )
                            """)).isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> statement.executeUpdate("""
                            INSERT INTO wallet_hold (
                                id, hold_no, user_id, business_type, amount, captured_amount,
                                released_amount, status, settlement_event_id, settled_at,
                                version, created_at, updated_at
                            ) VALUES (
                                204, 'REGISTRATION:204', 101, 'AUCTION_DEPOSIT', 100.00, 100.00,
                                0.00, 'CAPTURED', '01994cfd-e548-78e2-98f3-adb673d563a4',
                                '2026-09-17 00:59:59.999999', 1,
                                '2026-09-17 01:00:00.000000', '2026-09-17 02:00:00.000000'
                            )
                            """)).isInstanceOf(SQLException.class);
                }
            } finally {
                adminStatement.executeUpdate("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private static void assertExistingHoldRemainsHeld(Statement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("""
                SELECT status, captured_amount, released_amount, settlement_event_id, settled_at
                FROM wallet_hold
                WHERE id = 201
                """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("status")).isEqualTo("HELD");
            assertThat(resultSet.getBigDecimal("captured_amount")).isEqualByComparingTo("0.00");
            assertThat(resultSet.getBigDecimal("released_amount")).isEqualByComparingTo("0.00");
            assertThat(resultSet.getString("settlement_event_id")).isNull();
            assertThat(resultSet.getTimestamp("settled_at")).isNull();
        }
    }

    private static void insertAccount(Statement statement) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO user_account (
                    id, username, password_hash, nickname, status, version, created_at, updated_at
                ) VALUES (
                    101, 'migration_user', '{bcrypt}migration-test', 'Migration user', 'ACTIVE', 0,
                    '2026-09-17 01:00:00.000000', '2026-09-17 01:00:00.000000'
                )
                """);
    }

    private static void insertHeld(Statement statement, long id, String holdNo, String amount)
            throws SQLException {
        statement.executeUpdate("""
                INSERT INTO wallet_hold (
                    id, hold_no, user_id, business_type, amount, status, version, created_at, updated_at
                ) VALUES (
                    %d, '%s', 101, 'AUCTION_DEPOSIT', %s, 'HELD', 0,
                    '2026-09-17 01:10:00.000000', '2026-09-17 01:10:00.000000'
                )
                """.formatted(id, holdNo, amount));
    }

    private static Flyway flyway(String schemaUrl, String rootPassword, String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(schemaUrl, "root", rootPassword)
                .locations("classpath:db/migration")
                .defaultSchema(schema)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .baselineOnMigrate(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
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

    private static List<String> readColumns(String schemaUrl, String rootPassword, String tableName)
            throws Exception {
        return readNames(
                schemaUrl,
                rootPassword,
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
                tableName
        );
    }

    private static List<String> readIndexes(String schemaUrl, String rootPassword, String tableName)
            throws Exception {
        return readNames(
                schemaUrl,
                rootPassword,
                "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY INDEX_NAME",
                tableName
        );
    }

    private static List<String> readNames(
            String schemaUrl,
            String rootPassword,
            String sql,
            String tableName
    ) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(schemaUrl, "root", rootPassword);
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    names.add(resultSet.getString(1));
                }
            }
        }
        return names;
    }

    private static void createSchema(Statement statement, String schema) throws SQLException {
        statement.executeUpdate("CREATE DATABASE `" + schema
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
    }

    private static String randomSchema() {
        String schema = "tidebid_account_verify_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        assertThat(schema).matches("tidebid_account_verify_[0-9a-f]{12}");
        return schema;
    }

    private static DatabaseTarget databaseTarget() {
        String host = environmentOrDefault("TIDEBID_MYSQL_HOST", "127.0.0.1");
        String port = environmentOrDefault("TIDEBID_MYSQL_PORT", "13306");
        String rootPassword = System.getenv("TIDEBID_MYSQL_ROOT_PASSWORD");
        return new DatabaseTarget(host, port, rootPassword, jdbcUrl(host, port, ""));
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

    private record DatabaseTarget(String host, String port, String rootPassword, String serverUrl) {
    }
}

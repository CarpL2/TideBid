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
            "auction_bid_command",
            "auction_inbox",
            "auction_item",
            "auction_item_image",
            "auction_outbox",
            "auction_proxy_bid",
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

                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                assertThat(readTables(schemaUrl, rootPassword)).containsExactlyElementsOf(EXPECTED_TABLES);
                assertThat(readColumns(schemaUrl, rootPassword, "auction_session"))
                        .contains("original_end_at", "extension_count", "winner_id", "winning_bid_id",
                                "final_price", "closed_at");
                assertThat(readColumns(schemaUrl, rootPassword, "bid_record"))
                        .contains("source", "command_id");
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

                assertThat(recoveredFlyway.migrate().migrationsExecuted).isEqualTo(5);
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
                        .target("2")
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

    @Test
    void replaysOnlyPreexistingTerminalOutcomeEventsOnce() throws Exception {
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
                Flyway versionThree = Flyway.configure()
                        .dataSource(schemaUrl, "root", rootPassword)
                        .locations("classpath:db/migration")
                        .defaultSchema(schema)
                        .target("3")
                        .cleanDisabled(true)
                        .load();
                assertThat(versionThree.migrate().migrationsExecuted).isEqualTo(3);

                try (Connection connection = DriverManager.getConnection(schemaUrl, "root", rootPassword);
                     Statement statement = connection.createStatement()) {
                    insertPublishedOutbox(statement, 701,
                            "019947e0-e9d4-7f21-8d7a-3c74b922e171", "auction.closed-sold");
                    insertPublishedOutbox(statement, 702,
                            "019947e0-e9d4-7f21-8d7a-3c74b922e172", "auction.closed-unsold");
                    insertPublishedOutbox(statement, 703,
                            "019947e0-e9d4-7f21-8d7a-3c74b922e173", "deposit.settlement-requested");
                    insertPublishedOutbox(statement, 704,
                            "019947e0-e9d4-7f21-8d7a-3c74b922e174", "bid.accepted");
                }

                Flyway latest = Flyway.configure()
                        .dataSource(schemaUrl, "root", rootPassword)
                        .locations("classpath:db/migration")
                        .defaultSchema(schema)
                        .cleanDisabled(true)
                        .load();
                assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
                assertThat(latest.migrate().migrationsExecuted).isZero();

                try (Connection connection = DriverManager.getConnection(schemaUrl, "root", rootPassword);
                     Statement statement = connection.createStatement();
                     ResultSet replayed = statement.executeQuery("""
                             SELECT event_type, status, published_at
                             FROM auction_outbox
                             ORDER BY id
                             """)) {
                    assertThat(replayed.next()).isTrue();
                    assertThat(replayed.getString("event_type")).isEqualTo("auction.closed-sold");
                    assertThat(replayed.getString("status")).isEqualTo("PENDING");
                    assertThat(replayed.getTimestamp("published_at")).isNull();
                    assertThat(replayed.next()).isTrue();
                    assertThat(replayed.getString("event_type")).isEqualTo("auction.closed-unsold");
                    assertThat(replayed.getString("status")).isEqualTo("PENDING");
                    assertThat(replayed.getTimestamp("published_at")).isNull();
                    assertThat(replayed.next()).isTrue();
                    assertThat(replayed.getString("event_type")).isEqualTo("deposit.settlement-requested");
                    assertThat(replayed.getString("status")).isEqualTo("PENDING");
                    assertThat(replayed.getTimestamp("published_at")).isNull();
                    assertThat(replayed.next()).isTrue();
                    assertThat(replayed.getString("event_type")).isEqualTo("bid.accepted");
                    assertThat(replayed.getString("status")).isEqualTo("PUBLISHED");
                    assertThat(replayed.getTimestamp("published_at")).isNotNull();
                    assertThat(replayed.next()).isFalse();
                }
            } finally {
                adminStatement.executeUpdate("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    @Test
    void createsServiceLocalOutboxAndInboxWithRequiredConstraintsAndIndexes() throws Exception {
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
                Flyway versionTwo = Flyway.configure()
                        .dataSource(schemaUrl, "root", rootPassword)
                        .locations("classpath:db/migration")
                        .defaultSchema(schema)
                        .target("2")
                        .cleanDisabled(true)
                        .load();
                assertThat(versionTwo.migrate().migrationsExecuted).isEqualTo(2);

                try (Connection connection = DriverManager.getConnection(schemaUrl, "root", rootPassword);
                     Statement statement = connection.createStatement()) {
                    insertItem(statement, 103, 303);
                    insertSession(statement, 203, 103, 303, null, null, 0);
                }

                Flyway latest = Flyway.configure()
                        .dataSource(schemaUrl, "root", rootPassword)
                        .locations("classpath:db/migration")
                        .defaultSchema(schema)
                        .cleanDisabled(true)
                        .load();
                assertThat(latest.migrate().migrationsExecuted).isEqualTo(3);

                assertThat(readIndexes(schemaUrl, rootPassword, "auction_outbox"))
                        .contains(
                                "PRIMARY",
                                "uk_auction_outbox_event_id",
                                "idx_auction_outbox_pending_scan",
                                "idx_auction_outbox_lease_scan",
                                "idx_auction_outbox_aggregate"
                        );
                assertThat(readIndexes(schemaUrl, rootPassword, "auction_inbox"))
                        .contains(
                                "PRIMARY",
                                "uk_auction_inbox_consumer_event",
                                "idx_auction_inbox_processed"
                        );

                try (Connection connection = DriverManager.getConnection(schemaUrl, "root", rootPassword);
                     Statement statement = connection.createStatement()) {
                    try (ResultSet existing = statement.executeQuery("""
                            SELECT COUNT(*)
                            FROM auction_session
                            WHERE id = 203 AND status = 'AWAITING_CLOSE'
                            """)) {
                        assertThat(existing.next()).isTrue();
                        assertThat(existing.getInt(1)).isOne();
                    }

                    insertPendingOutbox(statement, 501, "019947e0-e9d4-7f21-8d7a-3c74b922e19a");
                    assertThatThrownBy(() -> insertPendingOutbox(
                            statement,
                            502,
                            "019947e0-e9d4-7f21-8d7a-3c74b922e19a"
                    )).isInstanceOf(SQLException.class);

                    assertThatThrownBy(() -> statement.executeUpdate("""
                            INSERT INTO auction_outbox (
                                id, event_id, aggregate_type, aggregate_id, event_type, schema_version,
                                topic, tag, message_key, payload, payload_hash, deliver_at, status,
                                attempt_count, next_attempt_at, lease_owner, lease_token, lease_until,
                                created_at, updated_at
                            ) VALUES (
                                503, '019947e0-e9d4-7f21-8d7a-3c74b922e19b', 'AUCTION', '201',
                                'auction.close', 1, 'tidebid-scheduled-commands', 'auction.close',
                                '019947e0-e9d4-7f21-8d7a-3c74b922e19b', JSON_OBJECT('eventId', 'test'),
                                REPEAT('b', 64), '2026-09-16 13:00:00.000000', 'PUBLISHING', 1,
                                '2026-09-16 12:00:00.000000', 'worker-1', NULL,
                                '2026-09-16 12:00:30.000000',
                                '2026-09-16 12:00:00.000000', '2026-09-16 12:00:00.000000'
                            )
                            """)).isInstanceOf(SQLException.class);

                    insertInbox(statement, 601, "tidebid-auction-close-v1",
                            "019947e0-e9d4-7f21-8d7a-3c74b922e19c", "c");
                    assertThatThrownBy(() -> insertInbox(
                            statement,
                            602,
                            "tidebid-auction-close-v1",
                            "019947e0-e9d4-7f21-8d7a-3c74b922e19c",
                            "d"
                    )).isInstanceOf(SQLException.class);
                    insertInbox(statement, 603, "tidebid-auction-close-v2",
                            "019947e0-e9d4-7f21-8d7a-3c74b922e19c", "d");

                    assertThatThrownBy(() -> insertInbox(
                            statement,
                            604,
                            "tidebid-auction-close-v1",
                            "019947e0-e9d4-7f21-8d7a-3c74b922e19d",
                            "not-hex"
                    )).isInstanceOf(SQLException.class);
                }
            } finally {
                adminStatement.executeUpdate("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    @Test
    void upgradesVersionFourSchemaToProxyBiddingModelWithoutLosingBidHistory() throws Exception {
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
                Flyway versionFour = Flyway.configure()
                        .dataSource(schemaUrl, "root", rootPassword)
                        .locations("classpath:db/migration")
                        .defaultSchema(schema)
                        .target("4")
                        .cleanDisabled(true)
                        .load();
                assertThat(versionFour.migrate().migrationsExecuted).isEqualTo(4);

                try (Connection connection = DriverManager.getConnection(schemaUrl, "root", rootPassword);
                     Statement statement = connection.createStatement()) {
                    insertItem(statement, 104, 304);
                    insertSession(statement, 204, 104, 304, "100.00", 401L, 1);
                    statement.executeUpdate("""
                            INSERT INTO bid_record (
                                id, auction_id, bidder_id, request_id, amount, previous_price, sequence_no, created_at
                            ) VALUES (
                                801, 204, 401, 'legacy_request_0001', 100.00, NULL, 1,
                                '2026-09-16 12:10:00.000000'
                            )
                            """);
                }

                Flyway latest = Flyway.configure()
                        .dataSource(schemaUrl, "root", rootPassword)
                        .locations("classpath:db/migration")
                        .defaultSchema(schema)
                        .cleanDisabled(true)
                        .load();
                assertThat(latest.migrate().migrationsExecuted).isOne();
                assertThat(latest.migrate().migrationsExecuted).isZero();

                assertThat(readTables(schemaUrl, rootPassword))
                        .contains("auction_proxy_bid", "auction_bid_command");
                assertThat(readIndexes(schemaUrl, rootPassword, "auction_proxy_bid"))
                        .contains("uk_auction_proxy_bid_auction_bidder", "idx_auction_proxy_bid_competition");
                assertThat(readIndexes(schemaUrl, rootPassword, "auction_bid_command"))
                        .contains("uk_auction_bid_command_actor_request", "idx_auction_bid_command_auction_created");
                assertThat(readIndexes(schemaUrl, rootPassword, "bid_record"))
                        .contains("idx_bid_record_bidder_request", "idx_bid_record_command_sequence")
                        .doesNotContain("uk_bid_record_bidder_request");

                try (Connection connection = DriverManager.getConnection(schemaUrl, "root", rootPassword);
                     Statement statement = connection.createStatement()) {
                    try (ResultSet migratedSession = statement.executeQuery("""
                            SELECT end_at, original_end_at, extension_count
                            FROM auction_session WHERE id = 204
                            """)) {
                        assertThat(migratedSession.next()).isTrue();
                        assertThat(migratedSession.getTimestamp("original_end_at"))
                                .isEqualTo(migratedSession.getTimestamp("end_at"));
                        assertThat(migratedSession.getInt("extension_count")).isZero();
                    }
                    try (ResultSet migratedBid = statement.executeQuery("""
                            SELECT source, command_id FROM bid_record WHERE id = 801
                            """)) {
                        assertThat(migratedBid.next()).isTrue();
                        assertThat(migratedBid.getString("source")).isEqualTo("MANUAL");
                        assertThat(migratedBid.getObject("command_id")).isNull();
                    }

                    assertThat(statement.executeUpdate("""
                            INSERT INTO auction_proxy_bid (
                                id, auction_id, bidder_id, max_amount, status, priority, version,
                                disabled_at, created_at, updated_at
                            ) VALUES (
                                901, 204, 401, 500.00, 'ACTIVE', 1, 0, NULL,
                                '2026-09-16 12:20:00.000000', '2026-09-16 12:20:00.000000'
                            )
                            """)).isOne();
                    assertThatThrownBy(() -> statement.executeUpdate("""
                            INSERT INTO auction_proxy_bid (
                                id, auction_id, bidder_id, max_amount, status, priority, version,
                                disabled_at, created_at, updated_at
                            ) VALUES (
                                902, 204, 401, 600.00, 'ACTIVE', 2, 0, NULL,
                                '2026-09-16 12:21:00.000000', '2026-09-16 12:21:00.000000'
                            )
                            """)).isInstanceOf(SQLException.class);

                    assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE auction_session SET extension_count = -1 WHERE id = 204
                            """)).isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE auction_session
                            SET end_at = DATE_SUB(original_end_at, INTERVAL 1 SECOND)
                            WHERE id = 204
                            """)).isInstanceOf(SQLException.class);

                    assertThat(statement.executeUpdate("""
                            INSERT INTO auction_bid_command (
                                id, auction_id, actor_id, request_id, command_type, payload_hash, status, created_at
                            ) VALUES (
                                903, 204, 402, 'proxy_request_0001', 'UPSERT_PROXY', REPEAT('a', 64),
                                'PROCESSING', '2026-09-16 12:22:00.000000'
                            )
                            """)).isOne();
                    assertThatThrownBy(() -> statement.executeUpdate("""
                            INSERT INTO auction_bid_command (
                                id, auction_id, actor_id, request_id, command_type, payload_hash, status, created_at
                            ) VALUES (
                                904, 204, 402, 'proxy_request_0001', 'UPSERT_PROXY', REPEAT('b', 64),
                                'PROCESSING', '2026-09-16 12:22:01.000000'
                            )
                            """)).isInstanceOf(SQLException.class);

                    assertThat(statement.executeUpdate("""
                            INSERT INTO bid_record (
                                id, auction_id, bidder_id, request_id, source, command_id,
                                amount, previous_price, sequence_no, created_at
                            ) VALUES
                                (802, 204, 402, 'proxy_request_0001', 'MANUAL', 903,
                                 110.00, 100.00, 2, '2026-09-16 12:22:02.000000'),
                                (803, 204, 402, 'proxy_request_0001', 'PROXY', 903,
                                 120.00, 110.00, 3, '2026-09-16 12:22:03.000000')
                            """)).isEqualTo(2);
                    assertThatThrownBy(() -> statement.executeUpdate("""
                            INSERT INTO bid_record (
                                id, auction_id, bidder_id, request_id, source, command_id,
                                amount, previous_price, sequence_no, created_at
                            ) VALUES (
                                804, 204, 403, 'invalid_source_0001', 'SYSTEM', 903,
                                130.00, 120.00, 4, '2026-09-16 12:22:04.000000'
                            )
                            """)).isInstanceOf(SQLException.class);
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

    private static List<String> readIndexes(
            String schemaUrl,
            String rootPassword,
            String tableName
    ) throws Exception {
        List<String> indexes = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(schemaUrl, "root", rootPassword);
             var statement = connection.prepareStatement(
                     "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY INDEX_NAME"
             )) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    indexes.add(resultSet.getString(1));
                }
            }
        }
        return indexes;
    }

    private static void insertPendingOutbox(Statement statement, long id, String eventId) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO auction_outbox (
                    id, event_id, aggregate_type, aggregate_id, event_type, schema_version,
                    topic, tag, message_key, payload, payload_hash, deliver_at, status,
                    attempt_count, next_attempt_at, created_at, updated_at
                ) VALUES (
                    %d, '%s', 'AUCTION', '201', 'auction.close', 1,
                    'tidebid-scheduled-commands', 'auction.close', '%s',
                    JSON_OBJECT('eventId', '%s'), REPEAT('a', 64),
                    '2026-09-16 13:00:00.000000', 'PENDING', 0,
                    '2026-09-16 12:00:00.000000',
                    '2026-09-16 12:00:00.000000', '2026-09-16 12:00:00.000000'
                )
                """.formatted(id, eventId, eventId, eventId));
    }

    private static void insertPublishedOutbox(
            Statement statement,
            long id,
            String eventId,
            String eventType
    ) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO auction_outbox (
                    id, event_id, aggregate_type, aggregate_id, event_type, schema_version,
                    topic, tag, message_key, payload, payload_hash, deliver_at, status,
                    attempt_count, next_attempt_at, published_at, created_at, updated_at
                ) VALUES (
                    %d, '%s', 'AUCTION', '201', '%s', 1,
                    'tidebid-auction-events', '%s', '%s',
                    JSON_OBJECT('eventId', '%s'), REPEAT('a', 64),
                    '2026-09-16 13:00:00.000000', 'PUBLISHED', 1,
                    '2026-09-16 12:00:00.000000', '2026-09-16 13:00:01.000000',
                    '2026-09-16 12:00:00.000000', '2026-09-16 13:00:01.000000'
                )
                """.formatted(id, eventId, eventType, eventType, eventId, eventId));
    }

    private static void insertInbox(
            Statement statement,
            long id,
            String consumerName,
            String eventId,
            String hashCharacter
    ) throws SQLException {
        String payloadHash = "not-hex".equals(hashCharacter)
                ? hashCharacter
                : hashCharacter.repeat(64);
        statement.executeUpdate("""
                INSERT INTO auction_inbox (
                    id, consumer_name, event_id, event_type, schema_version, payload_hash, processed_at
                ) VALUES (
                    %d, '%s', '%s', 'auction.close', 1, '%s', '2026-09-16 12:01:00.000000'
                )
                """.formatted(id, consumerName, eventId, payloadHash));
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

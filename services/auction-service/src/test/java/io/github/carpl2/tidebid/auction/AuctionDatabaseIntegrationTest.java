package io.github.carpl2.tidebid.auction;

import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tidebid.auction.account-client.internal-token=test-internal-token-with-at-least-32-characters"
)
@ActiveProfiles("local-db")
@EnabledIfEnvironmentVariable(named = "TIDEBID_AUCTION_DB_PASSWORD", matches = ".+")
class AuctionDatabaseIntegrationTest {

    private static final List<String> EXPECTED_BUSINESS_TABLES = List.of(
            "auction_inbox",
            "auction_item",
            "auction_item_image",
            "auction_outbox",
            "auction_registration",
            "auction_review",
            "auction_session",
            "bid_record"
    );

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migratesAuctionSchemaAndRepeatingMigrationIsANoOp() {
        assertThat(dataSource).isNotNull();
        assertThat(sqlSessionFactory.getConfiguration().isMapUnderscoreToCamelCase()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("tidebid_auction");

        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT TABLE_NAME
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_TYPE = 'BASE TABLE'
                ORDER BY TABLE_NAME
                """,
                String.class
        );
        assertThat(tables)
                .containsExactlyElementsOf(concatFlywayHistory(EXPECTED_BUSINESS_TABLES));

        Integer successfulVersionOne = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
                Integer.class
        );
        assertThat(successfulVersionOne).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void keepsMoneyAndUtcColumnsAtTheRequiredDatabasePrecision() {
        List<Map<String, Object>> moneyColumns = jdbcTemplate.queryForList(
                """
                SELECT TABLE_NAME, COLUMN_NAME, NUMERIC_PRECISION, NUMERIC_SCALE
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND DATA_TYPE = 'decimal'
                ORDER BY TABLE_NAME, ORDINAL_POSITION
                """
        );
        assertThat(moneyColumns).hasSize(8);
        assertThat(moneyColumns).allSatisfy(column -> {
            assertThat(((Number) column.get("NUMERIC_PRECISION")).intValue()).isEqualTo(19);
            assertThat(((Number) column.get("NUMERIC_SCALE")).intValue()).isEqualTo(2);
        });

        List<Map<String, Object>> utcColumns = jdbcTemplate.queryForList(
                """
                SELECT TABLE_NAME, COLUMN_NAME, DATETIME_PRECISION
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND DATA_TYPE = 'datetime'
                """
        );
        assertThat(utcColumns).isNotEmpty().allSatisfy(column ->
                assertThat(((Number) column.get("DATETIME_PRECISION")).intValue()).isEqualTo(6)
        );
    }

    @Test
    void createsRequiredUniqueConstraintsAndOperationalIndexes() {
        List<String> uniqueIndexes = jdbcTemplate.queryForList(
                """
                SELECT DISTINCT INDEX_NAME
                FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND NON_UNIQUE = 0
                  AND INDEX_NAME <> 'PRIMARY'
                ORDER BY INDEX_NAME
                """,
                String.class
        );
        assertThat(uniqueIndexes).contains(
                "uk_auction_item_image_object_key",
                "uk_auction_item_image_item_sort",
                "uk_auction_inbox_consumer_event",
                "uk_auction_outbox_event_id",
                "uk_auction_review_item_submission",
                "uk_auction_session_item_id",
                "uk_auction_registration_no",
                "uk_auction_registration_auction_bidder",
                "uk_bid_record_bidder_request",
                "uk_bid_record_auction_sequence"
        );

        List<String> indexes = jdbcTemplate.queryForList(
                """
                SELECT DISTINCT INDEX_NAME
                FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                ORDER BY INDEX_NAME
                """,
                String.class
        );
        assertThat(indexes).contains(
                "idx_auction_item_review_submitted",
                "idx_auction_inbox_processed",
                "idx_auction_outbox_pending_scan",
                "idx_auction_outbox_lease_scan",
                "idx_auction_session_lobby",
                "idx_auction_registration_recovery",
                "idx_bid_record_auction_created"
        );
    }

    @Test
    void databaseChecksRejectInvalidStateAndAuctionUserCannotReadAccountSchema() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO auction_item
                    (id, seller_id, title, description, category, item_condition, review_status,
                     submission_version, version, created_at, updated_at)
                VALUES
                    (990000000000000001, 1, 'Invalid state', 'Constraint verification item',
                     'OTHER', 'GOOD', 'NOT_A_STATE', 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """
        )).isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auction_item WHERE id = 990000000000000001",
                Integer.class
        )).isZero();

        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tidebid_account.user_account",
                Integer.class
        )).isInstanceOf(DataAccessException.class);
    }

    private static List<String> concatFlywayHistory(List<String> businessTables) {
        return java.util.stream.Stream.concat(
                        businessTables.stream(),
                        java.util.stream.Stream.of("flyway_schema_history")
                )
                .sorted()
                .toList();
    }
}

package io.github.carpl2.tidebid.account;

import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local-db")
@Import(AccountJwtTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TIDEBID_ACCOUNT_DB_PASSWORD", matches = ".+")
class AccountDatabaseIntegrationTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "account_inbox",
            "account_outbox",
            "flyway_schema_history",
            "user_account",
            "user_role",
            "wallet_account",
            "wallet_credit",
            "wallet_debit",
            "wallet_hold",
            "wallet_ledger"
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
    void migratesAccountSchemaAndCreatesPersistenceInfrastructure() {
        assertThat(dataSource).isNotNull();
        assertThat(flyway).isNotNull();
        assertThat(sqlSessionFactory.getConfiguration().isMapUnderscoreToCamelCase()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("tidebid_account");

        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT TABLE_NAME
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                ORDER BY TABLE_NAME
                """,
                String.class
        );
        assertThat(tables).containsAll(EXPECTED_TABLES);

        Integer successfulVersionOne = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
                Integer.class
        );
        assertThat(successfulVersionOne).isEqualTo(1);

        Integer successfulVersionTwo = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = TRUE",
                Integer.class
        );
        assertThat(successfulVersionTwo).isEqualTo(1);

        Integer successfulVersionThree = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success = TRUE",
                Integer.class
        );
        assertThat(successfulVersionThree).isEqualTo(1);

        Integer successfulVersionFour = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4' AND success = TRUE",
                Integer.class
        );
        assertThat(successfulVersionFour).isEqualTo(1);

        List<String> holdColumns = jdbcTemplate.queryForList(
                """
                SELECT COLUMN_NAME
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wallet_hold'
                ORDER BY ORDINAL_POSITION
                """,
                String.class
        );
        assertThat(holdColumns).contains(
                "captured_amount",
                "released_amount",
                "settlement_event_id",
                "settled_at"
        );

        List<String> holdIndexes = jdbcTemplate.queryForList(
                """
                SELECT DISTINCT INDEX_NAME
                FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wallet_hold'
                ORDER BY INDEX_NAME
                """,
                String.class
        );
        assertThat(holdIndexes).contains("uk_wallet_hold_settlement_event_id");
    }
}

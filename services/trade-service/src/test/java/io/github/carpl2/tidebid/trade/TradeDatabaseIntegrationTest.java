package io.github.carpl2.tidebid.trade;

import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local-db")
@EnabledIfEnvironmentVariable(named = "TIDEBID_TRADE_DB_PASSWORD", matches = ".+")
class TradeDatabaseIntegrationTest {

    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired SqlSessionFactory sqlSessionFactory;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void usesOnlyTradeSchemaAndCreatesPersistenceInfrastructure() {
        assertThat(dataSource).isNotNull();
        assertThat(flyway).isNotNull();
        assertThat(sqlSessionFactory.getConfiguration().isMapUnderscoreToCamelCase()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("tidebid_trade");
        assertThat(jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME",
                String.class
        )).containsAll(List.of("flyway_schema_history", "trade_order", "payment_attempt", "trade_outbox", "trade_inbox"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
                Integer.class
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME IN ('tidebid_account','tidebid_auction','tidebid_trade','tidebid_ai')",
                Integer.class
        )).isOne();
    }
}

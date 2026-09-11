package io.github.carpl2.tidebid.auction;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                assertThat(readTables(schemaUrl, rootPassword)).containsExactlyElementsOf(EXPECTED_TABLES);
            } finally {
                statement.executeUpdate("DROP DATABASE `" + schema + "`");
            }
        }
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

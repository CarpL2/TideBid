package io.github.carpl2.tidebid.account.infrastructure.messaging;

import io.github.carpl2.tidebid.account.infrastructure.config.AccountOutboxProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static io.github.carpl2.tidebid.account.infrastructure.messaging.JdbcAccountInboxRepository.InboxDecision.DUPLICATE;
import static io.github.carpl2.tidebid.account.infrastructure.messaging.JdbcAccountInboxRepository.InboxDecision.INSERTED;
import static io.github.carpl2.tidebid.account.infrastructure.messaging.JdbcAccountOutboxRepository.FailureResult.DEAD;
import static io.github.carpl2.tidebid.account.infrastructure.messaging.JdbcAccountOutboxRepository.FailureResult.RETRY_SCHEDULED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TIDEBID_MYSQL_ROOT_PASSWORD", matches = ".+")
class JdbcAccountMessagingRepositoryTest {

    @Test
    void leasesRetriesPublishesAndRejectsConflictingInboxReplay() throws Exception {
        DatabaseTarget target = databaseTarget();
        String schema = "tidebid_account_msg_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        String schemaUrl = jdbcUrl(target.host(), target.port(), schema);

        try (Connection admin = DriverManager.getConnection(target.serverUrl(), "root", target.rootPassword());
             Statement statement = admin.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + schema
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                Flyway flyway = Flyway.configure()
                        .dataSource(schemaUrl, "root", target.rootPassword())
                        .locations("classpath:db/migration")
                        .defaultSchema(schema)
                        .cleanDisabled(true)
                        .load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);

                DriverManagerDataSource dataSource =
                        new DriverManagerDataSource(schemaUrl, "root", target.rootPassword());
                AccountOutboxProperties properties = new AccountOutboxProperties(
                        Duration.ofSeconds(1),
                        10,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(4),
                        2,
                        Duration.ofHours(48));
                JdbcAccountOutboxRepository outbox =
                        new JdbcAccountOutboxRepository(dataSource, properties);
                SimpleMeterRegistry meters = new SimpleMeterRegistry();
                JdbcAccountInboxRepository inbox = new JdbcAccountInboxRepository(dataSource, meters);
                Instant now = Instant.parse("2026-09-17T00:00:00Z");

                String retryEventId = UUID.randomUUID().toString();
                outbox.enqueue(event(retryEventId, now), now);
                String farFutureEventId = UUID.randomUUID().toString();
                outbox.enqueue(event(farFutureEventId, now.plus(Duration.ofHours(49))), now);

                var firstClaim = outbox.claimBatch("publisher-a", now);
                assertThat(firstClaim).singleElement().satisfies(message -> {
                    assertThat(message.eventId()).isEqualTo(retryEventId);
                    assertThat(message.status()).isEqualTo("PUBLISHING");
                    assertThat(message.leaseOwner()).isEqualTo("publisher-a");
                });
                assertThat(outbox.claimBatch("publisher-b", now)).isEmpty();

                String firstLease = firstClaim.getFirst().leaseToken();
                assertThat(outbox.markFailed(retryEventId, firstLease, "BROKER_UNAVAILABLE", now))
                        .isEqualTo(RETRY_SCHEDULED);
                assertThat(outbox.claimBatch("publisher-b", now.plusMillis(999))).isEmpty();

                var retryClaim = outbox.claimBatch("publisher-b", now.plusSeconds(1));
                assertThat(retryClaim).singleElement()
                        .extracting(JdbcAccountOutboxRepository.OutboxEntity::eventId)
                        .isEqualTo(retryEventId);
                assertThat(outbox.markFailed(
                        retryEventId,
                        retryClaim.getFirst().leaseToken(),
                        "BROKER_UNAVAILABLE",
                        now.plusSeconds(1))).isEqualTo(DEAD);
                assertThat(outbox.findByEventId(retryEventId)).get()
                        .satisfies(message -> {
                            assertThat(message.status()).isEqualTo("DEAD");
                            assertThat(message.attemptCount()).isEqualTo(2);
                            assertThat(message.lastErrorCode()).isEqualTo("BROKER_UNAVAILABLE");
                        });

                String reclaimedEventId = UUID.randomUUID().toString();
                outbox.enqueue(event(reclaimedEventId, now), now);
                var expiredClaim = outbox.claimBatch("publisher-a", now).getFirst();
                var reclaimed = outbox.claimBatch("publisher-b", now.plusSeconds(31)).getFirst();
                assertThat(reclaimed.leaseToken()).isNotEqualTo(expiredClaim.leaseToken());
                assertThat(outbox.markPublished(reclaimedEventId, expiredClaim.leaseToken(), now.plusSeconds(31)))
                        .isFalse();
                assertThat(outbox.markPublished(reclaimedEventId, reclaimed.leaseToken(), now.plusSeconds(31)))
                        .isTrue();
                assertThat(outbox.findByEventId(reclaimedEventId)).get()
                        .satisfies(message -> {
                            assertThat(message.status()).isEqualTo("PUBLISHED");
                            assertThat(message.attemptCount()).isOne();
                            assertThat(message.publishedAt()).isEqualTo(now.plusSeconds(31));
                        });

                String consumedEventId = UUID.randomUUID().toString();
                var consumed = new JdbcAccountInboxRepository.InboxEntity(
                        "consumer-v1",
                        consumedEventId,
                        "auction.closed",
                        1,
                        "a".repeat(64),
                        now);
                assertThat(inbox.recordProcessed(consumed)).isEqualTo(INSERTED);
                assertThat(inbox.recordProcessed(new JdbcAccountInboxRepository.InboxEntity(
                        consumed.consumerName(),
                        consumed.eventId(),
                        consumed.eventType(),
                        consumed.schemaVersion(),
                        consumed.payloadHash(),
                        now.plusSeconds(1)))).isEqualTo(DUPLICATE);
                assertThatThrownBy(() -> inbox.recordProcessed(
                        new JdbcAccountInboxRepository.InboxEntity(
                                consumed.consumerName(),
                                consumed.eventId(),
                                consumed.eventType(),
                                consumed.schemaVersion(),
                                "b".repeat(64),
                                now.plusSeconds(2))))
                        .isInstanceOf(JdbcAccountInboxRepository.InboxReplayConflictException.class);
                assertThat(outbox.diagnostics(now.plus(Duration.ofHours(2))))
                        .isEqualTo(new JdbcAccountOutboxRepository.OutboxDiagnostics(1, 1, 0));
            } finally {
                statement.executeUpdate("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private static JdbcAccountOutboxRepository.NewOutboxEvent event(String eventId, Instant deliverAt) {
        return new JdbcAccountOutboxRepository.NewOutboxEvent(
                eventId,
                "AUCTION",
                "1001",
                "auction.closed",
                1,
                "tidebid-auction-events",
                "{\"eventId\":\"" + eventId + "\"}",
                "a".repeat(64),
                deliverAt);
    }

    private static DatabaseTarget databaseTarget() {
        String host = environmentOrDefault("TIDEBID_MYSQL_HOST", "127.0.0.1");
        String port = environmentOrDefault("TIDEBID_MYSQL_PORT", "13306");
        return new DatabaseTarget(
                host,
                port,
                System.getenv("TIDEBID_MYSQL_ROOT_PASSWORD"),
                jdbcUrl(host, port, ""));
    }

    private static String jdbcUrl(String host, String port, String schema) {
        return "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC"
                + "&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record DatabaseTarget(String host, String port, String rootPassword, String serverUrl) { }
}

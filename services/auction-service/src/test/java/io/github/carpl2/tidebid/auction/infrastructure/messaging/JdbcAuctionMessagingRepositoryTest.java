package io.github.carpl2.tidebid.auction.infrastructure.messaging;

import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionOutboxProperties;
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

import static io.github.carpl2.tidebid.auction.infrastructure.messaging.JdbcAuctionInboxRepository.InboxDecision.DUPLICATE;
import static io.github.carpl2.tidebid.auction.infrastructure.messaging.JdbcAuctionInboxRepository.InboxDecision.INSERTED;
import static io.github.carpl2.tidebid.auction.infrastructure.messaging.JdbcAuctionOutboxRepository.FailureResult.DEAD;
import static io.github.carpl2.tidebid.auction.infrastructure.messaging.JdbcAuctionOutboxRepository.FailureResult.RETRY_SCHEDULED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TIDEBID_MYSQL_ROOT_PASSWORD", matches = ".+")
class JdbcAuctionMessagingRepositoryTest {

    @Test
    void leasesRetriesPublishesAndRejectsConflictingInboxReplay() throws Exception {
        DatabaseTarget target = databaseTarget();
        String schema = "tidebid_auction_msg_"
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
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);

                DriverManagerDataSource dataSource =
                        new DriverManagerDataSource(schemaUrl, "root", target.rootPassword());
                AuctionOutboxProperties properties = new AuctionOutboxProperties(
                        Duration.ofSeconds(1),
                        10,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(4),
                        2,
                        Duration.ofHours(48));
                JdbcAuctionOutboxRepository outbox =
                        new JdbcAuctionOutboxRepository(dataSource, properties);
                SimpleMeterRegistry meters = new SimpleMeterRegistry();
                JdbcAuctionInboxRepository inbox = new JdbcAuctionInboxRepository(dataSource, meters);
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
                        .extracting(JdbcAuctionOutboxRepository.OutboxEntity::eventId)
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

                // The publisher does not hand a delay to RocketMQ outside its safe horizon.
                var entersSafeHorizon = outbox.claimBatch("publisher-b", now.plus(Duration.ofHours(1)));
                assertThat(entersSafeHorizon).singleElement()
                        .extracting(JdbcAuctionOutboxRepository.OutboxEntity::eventId)
                        .isEqualTo(farFutureEventId);
                assertThat(outbox.markPublished(
                        farFutureEventId,
                        entersSafeHorizon.getFirst().leaseToken(),
                        now.plus(Duration.ofHours(1)))).isTrue();

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

                // Broker ACK happened, then this publisher crashed before markPublished.
                String ackCrashEventId = UUID.randomUUID().toString();
                outbox.enqueue(event(ackCrashEventId, now), now);
                var acknowledgedButUnmarked = outbox.claimBatch("publisher-a", now).getFirst();
                var redelivered = outbox.claimBatch("publisher-b", now.plusSeconds(31)).getFirst();
                assertThat(redelivered.eventId()).isEqualTo(acknowledgedButUnmarked.eventId());
                assertThat(redelivered.leaseToken()).isNotEqualTo(acknowledgedButUnmarked.leaseToken());

                var replay = new JdbcAuctionInboxRepository.InboxEntity(
                        "consumer-v1",
                        ackCrashEventId,
                        "auction.closed",
                        1,
                        "a".repeat(64),
                        now.plusSeconds(31));
                assertThat(inbox.recordProcessed(replay)).isEqualTo(INSERTED);
                assertThat(inbox.recordProcessed(new JdbcAuctionInboxRepository.InboxEntity(
                        replay.consumerName(), replay.eventId(), replay.eventType(), replay.schemaVersion(),
                        replay.payloadHash(), now.plusSeconds(32)))).isEqualTo(DUPLICATE);
                assertThat(outbox.markPublished(
                        ackCrashEventId, redelivered.leaseToken(), now.plusSeconds(32))).isTrue();

                String consumedEventId = UUID.randomUUID().toString();
                var consumed = new JdbcAuctionInboxRepository.InboxEntity(
                        "consumer-v1",
                        consumedEventId,
                        "auction.closed",
                        1,
                        "a".repeat(64),
                        now);
                assertThat(inbox.recordProcessed(consumed)).isEqualTo(INSERTED);
                assertThat(inbox.recordProcessed(new JdbcAuctionInboxRepository.InboxEntity(
                        consumed.consumerName(),
                        consumed.eventId(),
                        consumed.eventType(),
                        consumed.schemaVersion(),
                        consumed.payloadHash(),
                        now.plusSeconds(1)))).isEqualTo(DUPLICATE);
                assertThatThrownBy(() -> inbox.recordProcessed(
                        new JdbcAuctionInboxRepository.InboxEntity(
                                consumed.consumerName(),
                                consumed.eventId(),
                                consumed.eventType(),
                                consumed.schemaVersion(),
                                "b".repeat(64),
                                now.plusSeconds(2))))
                        .isInstanceOf(JdbcAuctionInboxRepository.InboxReplayConflictException.class);
                assertThat(meters.counter("tidebid.inbox.consume", "service", "auction", "outcome", "inserted")
                        .count()).isEqualTo(2);
                assertThat(meters.counter("tidebid.inbox.consume", "service", "auction", "outcome", "duplicate")
                        .count()).isEqualTo(2);
                assertThat(meters.counter("tidebid.inbox.consume", "service", "auction", "outcome", "conflict")
                        .count()).isOne();
                assertThat(outbox.diagnostics(now.plus(Duration.ofHours(2))))
                        .isEqualTo(new JdbcAuctionOutboxRepository.OutboxDiagnostics(0, 1, 0));
            } finally {
                statement.executeUpdate("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private static JdbcAuctionOutboxRepository.NewOutboxEvent event(String eventId, Instant deliverAt) {
        return new JdbcAuctionOutboxRepository.NewOutboxEvent(
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

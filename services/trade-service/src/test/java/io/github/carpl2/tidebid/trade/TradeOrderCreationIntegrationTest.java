package io.github.carpl2.tidebid.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.carpl2.tidebid.contracts.AuctionClosedSoldEvent;
import io.github.carpl2.tidebid.contracts.AuctionClosedUnsoldEvent;
import io.github.carpl2.tidebid.contracts.DepositSettlementRequestedEvent;
import io.github.carpl2.tidebid.contracts.DepositSettlementType;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.RocketMqTopology;
import io.github.carpl2.tidebid.trade.application.TradeOrderCreationService;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeOutboxProperties;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeRocketMqProperties;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.JdbcTradeInboxRepository;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.JdbcTradeOutboxRepository;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.TradeAuctionResultHandler;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.TradeOutboxEventFactory;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.TradeRocketMqTransport;
import io.github.carpl2.tidebid.trade.infrastructure.persistence.JdbcTradeOrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TIDEBID_MYSQL_ROOT_PASSWORD", matches = ".+")
class TradeOrderCreationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-18T08:00:00Z");

    @Test
    void createsOneSnapshotAndCaptureRequestAndAbsorbsReplays() throws Exception {
        withDatabase(fixture -> {
            AuctionClosedSoldEvent sold = sold(7101L, "限量模型", "hold-7101", "120.00", "150.00");
            byte[] first = fixture.message(UUID.randomUUID(), sold, AuctionClosedSoldEvent.EVENT_TYPE);

            fixture.handle(first, AuctionClosedSoldEvent.EVENT_TYPE);
            fixture.handle(first, AuctionClosedSoldEvent.EVENT_TYPE);
            fixture.handle(fixture.message(UUID.randomUUID(), sold, AuctionClosedSoldEvent.EVENT_TYPE),
                    AuctionClosedSoldEvent.EVENT_TYPE);

            assertThat(fixture.count("trade_order")).isOne();
            assertThat(fixture.count("trade_outbox")).isOne();
            assertThat(fixture.count("trade_inbox")).isEqualTo(2);
            assertThat(fixture.jdbc.queryForMap("""
                    SELECT order_no, auction_id, item_id, winning_bid_id, seller_id, buyer_id,
                           item_title, winner_hold_no, final_price, status, seller_settlement_status,
                           captured_deposit_amount, payable_amount, auction_closed_at
                    FROM trade_order
                    """))
                    .containsEntry("order_no", "TB-7101")
                    .containsEntry("status", "PENDING_DEPOSIT")
                    .containsEntry("seller_settlement_status", "NOT_REQUIRED")
                    .containsEntry("item_title", "限量模型")
                    .containsEntry("winner_hold_no", "hold-7101")
                    .containsEntry("auction_id", 7101L)
                    .containsEntry("item_id", 8101L)
                    .containsEntry("winning_bid_id", 9101L)
                    .containsEntry("seller_id", 1101L)
                    .containsEntry("buyer_id", 2101L)
                    .containsEntry("captured_deposit_amount", null)
                    .containsEntry("payable_amount", null);

            String payloadJson = fixture.jdbc.queryForObject(
                    "SELECT payload FROM trade_outbox", String.class);
            EventEnvelope<?> decoded = fixture.mapper.readValue(payloadJson, EventEnvelope.class);
            assertThat(decoded.eventType()).isEqualTo(DepositSettlementRequestedEvent.EVENT_TYPE);
            DepositSettlementRequestedEvent capture = fixture.mapper.treeToValue(
                    fixture.mapper.readTree(payloadJson).get("payload"), DepositSettlementRequestedEvent.class);
            assertThat(capture.settlementType()).isEqualTo(DepositSettlementType.CAPTURE);
            assertThat(capture.auctionId()).isEqualTo(7101L);
            assertThat(capture.userId()).isEqualTo(2101L);
            assertThat(capture.holdNo()).isEqualTo("hold-7101");
            assertThat(capture.holdAmount()).isEqualByComparingTo("120.00");
            assertThat(capture.captureTargetAmount()).isEqualByComparingTo("150.00");
            assertThat(capture.orderId()).isPositive();

            AuctionClosedSoldEvent conflicting = sold(7101L, "已被篡改的标题", "hold-7101", "120.00", "150.00");
            assertThatThrownBy(() -> fixture.handle(
                    fixture.message(UUID.randomUUID(), conflicting, AuctionClosedSoldEvent.EVENT_TYPE),
                    AuctionClosedSoldEvent.EVENT_TYPE))
                    .isInstanceOf(TradeOrderCreationService.OrderCreationConflictException.class);
            assertThat(fixture.count("trade_inbox")).isEqualTo(2);
            assertThat(fixture.count("trade_order")).isOne();
            assertThat(fixture.count("trade_outbox")).isOne();
        });
    }

    @Test
    void recordsUnsoldOnlyAndRollsBackWhenCaptureOutboxCannotBeInserted() throws Exception {
        withDatabase(fixture -> {
            AuctionClosedUnsoldEvent unsold = new AuctionClosedUnsoldEvent(
                    7201L, 8201L, "无人出价拍品", 1201L, NOW.minusSeconds(120), NOW.minusSeconds(60));
            fixture.handle(fixture.message(UUID.randomUUID(), unsold, AuctionClosedUnsoldEvent.EVENT_TYPE),
                    AuctionClosedUnsoldEvent.EVENT_TYPE);
            assertThat(fixture.count("trade_inbox")).isOne();
            assertThat(fixture.count("trade_order")).isZero();
            assertThat(fixture.count("trade_outbox")).isZero();

            AuctionClosedSoldEvent sold = sold(7202L, "事务回滚拍品", "hold-7202", "80.00", "130.00");
            String deterministicEventId = TradeOutboxEventFactory
                    .deterministicCaptureEventId(sold.auctionId(), sold.winnerHoldNo()).toString();
            fixture.seedOutbox(deterministicEventId);

            assertThatThrownBy(() -> fixture.handle(
                    fixture.message(UUID.randomUUID(), sold, AuctionClosedSoldEvent.EVENT_TYPE),
                    AuctionClosedSoldEvent.EVENT_TYPE)).isInstanceOf(Exception.class);
            assertThat(fixture.count("trade_inbox")).isOne();
            assertThat(fixture.count("trade_order")).isZero();
            assertThat(fixture.count("trade_outbox")).isOne();
        });
    }

    @Test
    void concurrentDistinctDeliveriesCreateOneOrderAndOneCaptureRequest() throws Exception {
        withDatabase(fixture -> {
            AuctionClosedSoldEvent sold = sold(7301L, "并发成交拍品", "hold-7301", "100.00", "160.00");
            byte[] first = fixture.message(UUID.randomUUID(), sold, AuctionClosedSoldEvent.EVENT_TYPE);
            byte[] second = fixture.message(UUID.randomUUID(), sold, AuctionClosedSoldEvent.EVENT_TYPE);
            CountDownLatch start = new CountDownLatch(1);

            try (var executor = Executors.newFixedThreadPool(2)) {
                var one = executor.submit(() -> { start.await(); fixture.handle(first, AuctionClosedSoldEvent.EVENT_TYPE); return null; });
                var two = executor.submit(() -> { start.await(); fixture.handle(second, AuctionClosedSoldEvent.EVENT_TYPE); return null; });
                start.countDown();
                one.get(10, TimeUnit.SECONDS);
                two.get(10, TimeUnit.SECONDS);
            }

            assertThat(fixture.count("trade_order")).isOne();
            assertThat(fixture.count("trade_outbox")).isOne();
            assertThat(fixture.count("trade_inbox")).isEqualTo(2);
        });
    }

    private static AuctionClosedSoldEvent sold(
            long auctionId, String title, String holdNo, String deposit, String price
    ) {
        return new AuctionClosedSoldEvent(
                auctionId, auctionId + 1000, title, 1101L, 2101L, auctionId + 2000,
                holdNo, new java.math.BigDecimal(deposit), new java.math.BigDecimal(price),
                NOW.minusSeconds(120), NOW.minusSeconds(60));
    }

    private static void withDatabase(ThrowingConsumer<Fixture> test) throws Exception {
        DatabaseTarget target = databaseTarget();
        String schema = "tidebid_trade_order_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toLowerCase(Locale.ROOT);
        String schemaUrl = jdbcUrl(target.host(), target.port(), schema);
        try (Connection admin = DriverManager.getConnection(target.serverUrl(), "root", target.rootPassword());
             Statement statement = admin.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + schema
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                Flyway.configure().dataSource(schemaUrl, "root", target.rootPassword())
                        .locations("classpath:db/migration").defaultSchema(schema).cleanDisabled(true).load().migrate();
                test.accept(new Fixture(schemaUrl, target.rootPassword()));
            } finally {
                statement.executeUpdate("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private static final class Fixture {
        private final DriverManagerDataSource dataSource;
        private final JdbcTemplate jdbc;
        private final ObjectMapper mapper;
        private final TradeAuctionResultHandler handler;
        private final TransactionTemplate transactions;

        private Fixture(String url, String password) {
            dataSource = new DriverManagerDataSource(url, "root", password);
            jdbc = new JdbcTemplate(dataSource);
            mapper = new ObjectMapper().findAndRegisterModules();
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            var properties = new TradeRocketMqProperties(
                    "127.0.0.1:8081", Duration.ofSeconds(3), 2,
                    new TradeRocketMqProperties.Topics(
                            RocketMqTopology.TRADE_EVENTS_TOPIC,
                            RocketMqTopology.AUCTION_EVENTS_TOPIC,
                            RocketMqTopology.ACCOUNT_EVENTS_TOPIC,
                            RocketMqTopology.SCHEDULED_COMMANDS_TOPIC),
                    new TradeRocketMqProperties.ConsumerGroups(
                            RocketMqTopology.TRADE_AUCTION_CONSUMER_GROUP,
                            RocketMqTopology.TRADE_ACCOUNT_CONSUMER_GROUP,
                            RocketMqTopology.TRADE_TIMEOUT_CONSUMER_GROUP));
            var outbox = new JdbcTradeOutboxRepository(dataSource, new TradeOutboxProperties(
                    Duration.ofSeconds(1), 10, Duration.ofSeconds(30), Duration.ofSeconds(1),
                    Duration.ofSeconds(8), 5, Duration.ofHours(48)));
            var service = new TradeOrderCreationService(
                    new JdbcTradeOrderRepository(dataSource), IdWorker::getId,
                    outbox, new TradeOutboxEventFactory(mapper), clock);
            handler = new TradeAuctionResultHandler(
                    properties, service, new JdbcTradeInboxRepository(dataSource, new SimpleMeterRegistry()),
                    mapper, clock);
            transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        }

        private byte[] message(UUID eventId, Object payload, String type) throws Exception {
            EventEnvelope<Object> envelope = new EventEnvelope<>(
                    eventId, type, 1, NOW.minusSeconds(30), "tidebid-auction-service",
                    "trace-order-123", payload);
            return mapper.writeValueAsBytes(envelope);
        }

        private void handle(byte[] body, String type) {
            String eventId;
            try {
                eventId = mapper.readTree(body).get("eventId").asText();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            var message = new TradeRocketMqTransport.InboundMessage(
                    UUID.randomUUID().toString(), RocketMqTopology.AUCTION_EVENTS_TOPIC, type,
                    List.of(eventId), Map.of(
                            "eventId", eventId,
                            "eventType", type,
                            "schemaVersion", "1",
                            "payloadHash", sha256(body)), body, 1);
            transactions.executeWithoutResult(ignored -> handler.handle(message));
        }

        private long count(String table) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        }

        private void seedOutbox(String eventId) {
            jdbc.update("""
                    INSERT INTO trade_outbox (
                        id, event_id, aggregate_type, aggregate_id, event_type, schema_version,
                        topic, tag, message_key, payload, payload_hash, deliver_at, status,
                        attempt_count, next_attempt_at, created_at, updated_at
                    ) VALUES (?, ?, 'TEST', 'seed', ?, 1, ?, ?, ?, CAST(? AS JSON), ?, ?,
                              'PENDING', 0, ?, ?, ?)
                    """, IdWorker.getId(), eventId, DepositSettlementRequestedEvent.EVENT_TYPE,
                    RocketMqTopology.TRADE_EVENTS_TOPIC, DepositSettlementRequestedEvent.EVENT_TYPE,
                    eventId, "{}", "a".repeat(64), java.sql.Timestamp.from(NOW),
                    java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static DatabaseTarget databaseTarget() {
        String host = environmentOrDefault("TIDEBID_MYSQL_HOST", "127.0.0.1");
        String port = environmentOrDefault("TIDEBID_MYSQL_PORT", "13306");
        return new DatabaseTarget(host, port, System.getenv("TIDEBID_MYSQL_ROOT_PASSWORD"), jdbcUrl(host, port, ""));
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

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}

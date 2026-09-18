package io.github.carpl2.tidebid.trade;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.contracts.AuctionClosedSoldEvent;
import io.github.carpl2.tidebid.contracts.DepositSettlementType;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.OrderPaidEvent;
import io.github.carpl2.tidebid.contracts.OrderPaymentTimeoutCommand;
import io.github.carpl2.tidebid.contracts.SellerCreditReason;
import io.github.carpl2.tidebid.contracts.SellerCreditedEvent;
import io.github.carpl2.tidebid.contracts.RocketMqTopology;
import io.github.carpl2.tidebid.contracts.SellerCreditRequestedEvent;
import io.github.carpl2.tidebid.contracts.WalletHoldSettledEvent;
import io.github.carpl2.tidebid.contracts.WalletHoldSettlementStatus;
import io.github.carpl2.tidebid.trade.application.TradeDepositSettlementService;
import io.github.carpl2.tidebid.trade.application.TradeSellerSettlementService;
import io.github.carpl2.tidebid.trade.application.TradeOrderCreationService;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeOrderProperties;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeOutboxProperties;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeRocketMqProperties;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.JdbcTradeInboxRepository;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.JdbcTradeOutboxRepository;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.TradeAccountResultHandler;
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

import java.math.BigDecimal;
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
class TradeDepositSettlementIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-18T09:00:00Z");

    @Test
    void sellerCreditCompletionIsIdempotentAndRejectsMismatchedResults() throws Exception {
        withDatabase(fixture -> {
            long auctionId = 90020L;
            long orderId = fixture.createOrder(auctionId, "1000.00", "1000.00");
            fixture.handleAccount(fixture.settlementMessage(
                    UUID.randomUUID(), orderId, auctionId,
                    "1000.00", "1000.00", "1000.00", "0.00"));
            Map<String, Object> pending = fixture.order(orderId);
            String creditNo = (String) pending.get("seller_credit_no");
            String orderNo = (String) pending.get("order_no");
            UUID eventId = UUID.randomUUID();
            SellerCreditedEvent credited = new SellerCreditedEvent(
                    SellerCreditReason.SALE_PROCEEDS, creditNo, orderId, orderNo,
                    auctionId, 1101L, money("1000.00"), NOW.minusSeconds(1));
            byte[] body = fixture.message(
                    eventId, credited, SellerCreditedEvent.EVENT_TYPE, "tidebid-account-service");

            fixture.handleAccount(body, SellerCreditedEvent.EVENT_TYPE);
            fixture.handleAccount(body, SellerCreditedEvent.EVENT_TYPE);

            Map<String, Object> completed = fixture.order(orderId);
            assertThat(completed.get("seller_settlement_status")).isEqualTo("COMPLETED");
            assertThat(completed.get("seller_credited_at")).isNotNull();
            assertThat(fixture.accountInboxCount()).isEqualTo(2);

            UUID conflictId = UUID.randomUUID();
            SellerCreditedEvent conflict = new SellerCreditedEvent(
                    SellerCreditReason.SALE_PROCEEDS, creditNo, orderId, orderNo,
                    auctionId, 1101L, money("999.00"), NOW.minusSeconds(1));
            byte[] conflictingBody = fixture.message(
                    conflictId, conflict, SellerCreditedEvent.EVENT_TYPE, "tidebid-account-service");
            assertThatThrownBy(() -> fixture.handleAccount(
                    conflictingBody, SellerCreditedEvent.EVENT_TYPE))
                    .hasMessageContaining("does not match");
            assertThat(fixture.inboxCount(conflictId)).isZero();
        });
    }

    @Test
    void movesToPendingPaymentOrDirectlyPaidAndAbsorbsReplays() throws Exception {
        withDatabase(fixture -> {
            long tailOrder = fixture.createOrder(8101L, "100.00", "150.00");
            byte[] tailResult = fixture.settlementMessage(
                    UUID.randomUUID(), tailOrder, 8101L, "100.00", "150.00", "100.00", "0.00");
            fixture.handleAccount(tailResult);
            fixture.handleAccount(tailResult);
            fixture.handleAccount(fixture.settlementMessage(
                    UUID.randomUUID(), tailOrder, 8101L, "100.00", "150.00", "100.00", "0.00"));

            Map<String, Object> tail = fixture.order(tailOrder);
            assertThat(tail).containsEntry("status", "PENDING_PAYMENT")
                    .containsEntry("seller_settlement_status", "NOT_REQUIRED");
            assertThat((BigDecimal) tail.get("captured_deposit_amount")).isEqualByComparingTo("100.00");
            assertThat((BigDecimal) tail.get("payable_amount")).isEqualByComparingTo("50.00");
            assertThat(((java.time.LocalDateTime) tail.get("payment_deadline")).toInstant(ZoneOffset.UTC))
                    .isEqualTo(NOW.plus(Duration.ofMinutes(30)));
            assertThat(fixture.outboxCount(tailOrder, OrderPaymentTimeoutCommand.EVENT_TYPE)).isOne();
            fixture.replayOrder(8101L, "100.00", "150.00");
            assertThat(fixture.order(tailOrder)).containsEntry("status", "PENDING_PAYMENT");

            long paidOrder = fixture.createOrder(8102L, "160.00", "150.00");
            fixture.handleAccount(fixture.settlementMessage(
                    UUID.randomUUID(), paidOrder, 8102L, "160.00", "150.00", "150.00", "10.00"));
            Map<String, Object> paid = fixture.order(paidOrder);
            assertThat(paid).containsEntry("status", "PAID")
                    .containsEntry("seller_settlement_status", "PENDING")
                    .containsEntry("seller_receivable_amount", new BigDecimal("150.00"));
            assertThat((BigDecimal) paid.get("captured_deposit_amount")).isEqualByComparingTo("150.00");
            assertThat((BigDecimal) paid.get("payable_amount")).isEqualByComparingTo("0.00");
            assertThat(paid.get("payment_deadline")).isNull();
            assertThat(fixture.outboxCount(paidOrder, OrderPaidEvent.EVENT_TYPE)).isOne();
            assertThat(fixture.outboxCount(paidOrder, SellerCreditRequestedEvent.EVENT_TYPE)).isOne();
            assertThat(fixture.outboxCount(paidOrder, OrderPaymentTimeoutCommand.EVENT_TYPE)).isZero();
        });
    }

    @Test
    void rejectsUnknownOrMismatchedResultsAndRollsBackWhenOutboxFails() throws Exception {
        withDatabase(fixture -> {
            long orderId = fixture.createOrder(8201L, "100.00", "150.00");
            long inboxBefore = fixture.accountInboxCount();
            assertThatThrownBy(() -> fixture.handleAccount(fixture.settlementMessage(
                    UUID.randomUUID(), orderId, 9999L, "100.00", "150.00", "100.00", "0.00")))
                    .isInstanceOf(TradeDepositSettlementService.DepositResultConflictException.class);
            assertThatThrownBy(() -> fixture.handleAccount(fixture.settlementMessage(
                    UUID.randomUUID(), orderId + 999L, 8201L, "100.00", "150.00", "100.00", "0.00")))
                    .isInstanceOf(TradeDepositSettlementService.DepositResultConflictException.class);
            assertThat(fixture.accountInboxCount()).isEqualTo(inboxBefore);
            assertThat(fixture.order(orderId)).containsEntry("status", "PENDING_DEPOSIT");

            fixture.seedOutbox(TradeOutboxEventFactory
                    .deterministicEventId("payment-timeout", orderId).toString());
            assertThatThrownBy(() -> fixture.handleAccount(fixture.settlementMessage(
                    UUID.randomUUID(), orderId, 8201L, "100.00", "150.00", "100.00", "0.00")))
                    .isInstanceOf(Exception.class);
            assertThat(fixture.order(orderId)).containsEntry("status", "PENDING_DEPOSIT");
            assertThat(fixture.accountInboxCount()).isEqualTo(inboxBefore);
        });
    }

    @Test
    void concurrentEquivalentResultsAdvanceTheOrderOnlyOnce() throws Exception {
        withDatabase(fixture -> {
            long orderId = fixture.createOrder(8301L, "100.00", "150.00");
            byte[] first = fixture.settlementMessage(
                    UUID.randomUUID(), orderId, 8301L, "100.00", "150.00", "100.00", "0.00");
            byte[] second = fixture.settlementMessage(
                    UUID.randomUUID(), orderId, 8301L, "100.00", "150.00", "100.00", "0.00");
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var one = executor.submit(() -> { start.await(); fixture.handleAccount(first); return null; });
                var two = executor.submit(() -> { start.await(); fixture.handleAccount(second); return null; });
                start.countDown();
                one.get(10, TimeUnit.SECONDS);
                two.get(10, TimeUnit.SECONDS);
            }
            assertThat(fixture.order(orderId)).containsEntry("status", "PENDING_PAYMENT");
            assertThat(fixture.outboxCount(orderId, OrderPaymentTimeoutCommand.EVENT_TYPE)).isOne();
            assertThat(fixture.accountInboxCount()).isEqualTo(2);
        });
    }

    private static void withDatabase(ThrowingConsumer<Fixture> test) throws Exception {
        DatabaseTarget target = databaseTarget();
        String schema = "tidebid_trade_settle_"
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
        private final JdbcTemplate jdbc;
        private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        private final TradeAuctionResultHandler auctionHandler;
        private final TradeAccountResultHandler accountHandler;
        private final TransactionTemplate transactions;
        private final TradeRocketMqProperties messaging;

        private Fixture(String url, String password) {
            var dataSource = new DriverManagerDataSource(url, "root", password);
            jdbc = new JdbcTemplate(dataSource);
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            messaging = properties();
            var outbox = new JdbcTradeOutboxRepository(dataSource, new TradeOutboxProperties(
                    Duration.ofSeconds(1), 10, Duration.ofSeconds(30), Duration.ofSeconds(1),
                    Duration.ofSeconds(8), 5, Duration.ofHours(48)));
            var eventFactory = new TradeOutboxEventFactory(mapper);
            var inbox = new JdbcTradeInboxRepository(dataSource, new SimpleMeterRegistry());
            var creation = new TradeOrderCreationService(
                    new JdbcTradeOrderRepository(dataSource), IdWorker::getId, outbox, eventFactory, clock);
            var settlement = new TradeDepositSettlementService(
                    dataSource, new TradeOrderProperties(Duration.ofMinutes(30)), outbox, eventFactory, clock);
            auctionHandler = new TradeAuctionResultHandler(messaging, creation, inbox, mapper, clock);
            var sellerSettlement = new TradeSellerSettlementService(dataSource, clock);
            accountHandler = new TradeAccountResultHandler(
                    messaging, settlement, sellerSettlement, inbox, mapper, clock);
            transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        }

        private long createOrder(long auctionId, String holdAmount, String finalPrice) throws Exception {
            AuctionClosedSoldEvent sold = sold(auctionId, holdAmount, finalPrice);
            byte[] body = message(UUID.randomUUID(), sold, AuctionClosedSoldEvent.EVENT_TYPE,
                    "tidebid-auction-service");
            handle(auctionHandler, body, RocketMqTopology.AUCTION_EVENTS_TOPIC,
                    AuctionClosedSoldEvent.EVENT_TYPE);
            return jdbc.queryForObject(
                    "SELECT id FROM trade_order WHERE auction_id = ?", Long.class, auctionId);
        }

        private void replayOrder(long auctionId, String holdAmount, String finalPrice) throws Exception {
            byte[] body = message(UUID.randomUUID(), sold(auctionId, holdAmount, finalPrice),
                    AuctionClosedSoldEvent.EVENT_TYPE, "tidebid-auction-service");
            handle(auctionHandler, body, RocketMqTopology.AUCTION_EVENTS_TOPIC,
                    AuctionClosedSoldEvent.EVENT_TYPE);
        }

        private AuctionClosedSoldEvent sold(long auctionId, String holdAmount, String finalPrice) {
            return new AuctionClosedSoldEvent(
                    auctionId, auctionId + 1000, "拍品-" + auctionId, 1101L, 2101L,
                    auctionId + 2000, "hold-" + auctionId, money(holdAmount), money(finalPrice),
                    NOW.minusSeconds(120), NOW.minusSeconds(60));
        }

        private byte[] settlementMessage(
                UUID eventId, long orderId, long auctionId, String holdAmount,
                String target, String captured, String released
        ) throws Exception {
            WalletHoldSettledEvent result = new WalletHoldSettledEvent(
                    DepositSettlementType.CAPTURE, WalletHoldSettlementStatus.CAPTURED,
                    auctionId, orderId, 2101L, "hold-" + auctionId, money(holdAmount),
                    money(target), money(captured), money(released), NOW.minusSeconds(10));
            return message(eventId, result, WalletHoldSettledEvent.EVENT_TYPE, "tidebid-account-service");
        }

        private byte[] message(UUID eventId, Object payload, String type, String producer) throws Exception {
            return mapper.writeValueAsBytes(new EventEnvelope<>(
                    eventId, type, 1, NOW.minusSeconds(5), producer, "trace-settle-123", payload));
        }

        private void handleAccount(byte[] body) {
            handleAccount(body, WalletHoldSettledEvent.EVENT_TYPE);
        }

        private void handleAccount(byte[] body, String eventType) {
            handle(accountHandler, body, RocketMqTopology.ACCOUNT_EVENTS_TOPIC, eventType);
        }

        private void handle(
                TradeRocketMqTransport.InboundHandler handler, byte[] body, String topic, String type
        ) {
            try {
                String eventId = mapper.readTree(body).get("eventId").asText();
                var message = new TradeRocketMqTransport.InboundMessage(
                        UUID.randomUUID().toString(), topic, type, List.of(eventId),
                        Map.of("eventId", eventId, "eventType", type, "schemaVersion", "1",
                                "payloadHash", sha256(body)), body, 1);
                transactions.executeWithoutResult(ignored -> handler.handle(message));
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private Map<String, Object> order(long orderId) {
            return jdbc.queryForMap("SELECT * FROM trade_order WHERE id = ?", orderId);
        }

        private long outboxCount(long orderId, String eventType) {
            return jdbc.queryForObject("""
                    SELECT COUNT(*) FROM trade_outbox WHERE aggregate_id = ? AND event_type = ?
                    """, Long.class, Long.toString(orderId), eventType);
        }

        private long accountInboxCount() {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM trade_inbox WHERE consumer_name = ?", Long.class,
                    messaging.consumerGroups().accountResults());
        }

        private long inboxCount(UUID eventId) {
            return jdbc.queryForObject("""
                    SELECT COUNT(*) FROM trade_inbox WHERE consumer_name = ? AND event_id = ?
                    """, Long.class, messaging.consumerGroups().accountResults(), eventId.toString());
        }

        private void seedOutbox(String eventId) {
            jdbc.update("""
                    INSERT INTO trade_outbox (
                        id, event_id, aggregate_type, aggregate_id, event_type, schema_version,
                        topic, tag, message_key, payload, payload_hash, deliver_at, status,
                        attempt_count, next_attempt_at, created_at, updated_at
                    ) VALUES (?, ?, 'TEST', 'seed', ?, 1, ?, ?, ?, CAST(? AS JSON), ?, ?,
                              'PENDING', 0, ?, ?, ?)
                    """, IdWorker.getId(), eventId, OrderPaymentTimeoutCommand.EVENT_TYPE,
                    RocketMqTopology.SCHEDULED_COMMANDS_TOPIC, OrderPaymentTimeoutCommand.EVENT_TYPE,
                    eventId, "{}", "a".repeat(64), java.sql.Timestamp.from(NOW),
                    java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        }
    }

    private static TradeRocketMqProperties properties() {
        return new TradeRocketMqProperties(
                "127.0.0.1:8081", Duration.ofSeconds(3), 2,
                new TradeRocketMqProperties.Topics(
                        RocketMqTopology.TRADE_EVENTS_TOPIC, RocketMqTopology.AUCTION_EVENTS_TOPIC,
                        RocketMqTopology.ACCOUNT_EVENTS_TOPIC, RocketMqTopology.SCHEDULED_COMMANDS_TOPIC),
                new TradeRocketMqProperties.ConsumerGroups(
                        RocketMqTopology.TRADE_AUCTION_CONSUMER_GROUP,
                        RocketMqTopology.TRADE_ACCOUNT_CONSUMER_GROUP,
                        RocketMqTopology.TRADE_TIMEOUT_CONSUMER_GROUP));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
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
        return new DatabaseTarget(host, port, System.getenv("TIDEBID_MYSQL_ROOT_PASSWORD"),
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

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}

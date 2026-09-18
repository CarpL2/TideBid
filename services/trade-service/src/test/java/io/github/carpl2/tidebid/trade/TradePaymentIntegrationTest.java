package io.github.carpl2.tidebid.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.contracts.OrderPaidEvent;
import io.github.carpl2.tidebid.contracts.SellerCreditRequestedEvent;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.trade.application.PaymentAttemptSnapshot;
import io.github.carpl2.tidebid.trade.application.TradeOrderQueryService;
import io.github.carpl2.tidebid.trade.application.TradePaymentTransaction;
import io.github.carpl2.tidebid.trade.application.port.AccountDebitPort;
import io.github.carpl2.tidebid.trade.domain.TradeErrorCode;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeOutboxProperties;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradePaymentProperties;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.JdbcTradeOutboxRepository;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.TradeOutboxEventFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TIDEBID_MYSQL_ROOT_PASSWORD", matches = ".+")
class TradePaymentIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final long BUYER = 2101L;
    private static final long SELLER = 1101L;

    @Test
    void createsStableAttemptAndMovesPaidExactlyOnce() throws Exception {
        withDatabase(fixture -> {
            long orderId = fixture.seedOrder(9101L);
            TradePaymentTransaction.StartResult start = fixture.begin(BUYER, orderId, "pay_req_0001");

            assertThat(start.shouldCallAccount()).isTrue();
            assertThat(start.attempt().paymentNo()).startsWith("PAY:");
            assertThat(start.attempt().amount()).isEqualByComparingTo("100.00");
            assertThat(fixture.orderStatus(orderId)).isEqualTo("PAYMENT_PROCESSING");

            PaymentAttemptSnapshot completed = fixture.apply(start.attempt().id(), new AccountDebitPort.Succeeded(
                    start.attempt().paymentNo(), BUYER, orderId, money("100.00"), NOW));
            assertThat(completed.status()).isEqualTo("SUCCEEDED");
            assertThat(fixture.orderStatus(orderId)).isEqualTo("PAID");
            assertThat(fixture.outboxCount(orderId, OrderPaidEvent.EVENT_TYPE)).isOne();
            assertThat(fixture.outboxCount(orderId, SellerCreditRequestedEvent.EVENT_TYPE)).isOne();

            TradePaymentTransaction.StartResult replay = fixture.begin(BUYER, orderId, "pay_req_0001");
            assertThat(replay.shouldCallAccount()).isFalse();
            assertThat(replay.attempt()).isEqualTo(completed);
            assertThat(fixture.attemptCount()).isOne();
        });
    }

    @Test
    void deterministicRejectionReturnsOrderToPayableAndUnknownKeepsProcessing() throws Exception {
        withDatabase(fixture -> {
            long rejectedOrder = fixture.seedOrder(9201L);
            var rejectedStart = fixture.begin(BUYER, rejectedOrder, "pay_req_0002");
            PaymentAttemptSnapshot rejected = fixture.apply(rejectedStart.attempt().id(),
                    new AccountDebitPort.Rejected(rejectedStart.attempt().paymentNo(), BUYER,
                            rejectedOrder, money("100.00"), "INSUFFICIENT_BALANCE", NOW));
            assertThat(rejected.status()).isEqualTo("REJECTED");
            assertThat(rejected.failureCode()).isEqualTo("INSUFFICIENT_BALANCE");
            assertThat(fixture.orderStatus(rejectedOrder)).isEqualTo("PENDING_PAYMENT");

            var second = fixture.begin(BUYER, rejectedOrder, "pay_req_0003");
            PaymentAttemptSnapshot unknown = fixture.apply(second.attempt().id(), new AccountDebitPort.Unknown());
            assertThat(unknown.status()).isEqualTo("UNKNOWN");
            assertThat(unknown.nextRecoveryAt()).isEqualTo(NOW.plusSeconds(5));
            assertThat(fixture.orderStatus(rejectedOrder)).isEqualTo("PAYMENT_PROCESSING");
            assertThat(fixture.begin(BUYER, rejectedOrder, "pay_req_0003").shouldCallAccount()).isFalse();
        });
    }

    @Test
    void enforcesOwnershipDeadlineIdempotencyAndOneConcurrentAttempt() throws Exception {
        withDatabase(fixture -> {
            long firstOrder = fixture.seedOrder(9301L);
            assertThatThrownBy(() -> fixture.begin(BUYER + 1, firstOrder, "pay_req_0004"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(TradeErrorCode.ORDER_FORBIDDEN));

            long expired = fixture.seedOrder(9302L);
            fixture.jdbc.update("UPDATE trade_order SET payment_deadline = ? WHERE id = ?",
                    Timestamp.from(NOW), expired);
            assertThatThrownBy(() -> fixture.begin(BUYER, expired, "pay_req_0005"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(TradeErrorCode.PAYMENT_DEADLINE_EXPIRED));

            var first = fixture.begin(BUYER, firstOrder, "pay_req_0006");
            long otherOrder = fixture.seedOrder(9303L);
            assertThatThrownBy(() -> fixture.begin(BUYER, otherOrder, "pay_req_0006"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(TradeErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT));
            assertThat(first.attempt().orderId()).isEqualTo(firstOrder);

            long concurrentOrder = fixture.seedOrder(9304L);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var one = executor.submit(() -> fixture.tryBegin(start, concurrentOrder, "pay_req_0007"));
                var two = executor.submit(() -> fixture.tryBegin(start, concurrentOrder, "pay_req_0008"));
                start.countDown();
                Object resultOne = one.get(10, TimeUnit.SECONDS);
                Object resultTwo = two.get(10, TimeUnit.SECONDS);
                assertThat(java.util.List.of(resultOne, resultTwo)
                        .stream().filter(TradePaymentTransaction.StartResult.class::isInstance).count()).isOne();
                assertThat(java.util.List.of(resultOne, resultTwo)
                        .stream().filter(BusinessException.class::isInstance).count()).isOne();
            }
            assertThat(fixture.attemptCount(concurrentOrder)).isOne();
        });
    }

    @Test
    void queryReturnsOnlyCurrentBuyerAndProtectsDetail() throws Exception {
        withDatabase(fixture -> {
            long first = fixture.seedOrder(9401L);
            fixture.seedOrder(9402L);
            var page = fixture.queries.findMine(BUYER, 1, 1);
            assertThat(page.total()).isEqualTo(2);
            assertThat(page.items()).hasSize(1);
            assertThat(page.items().getFirst().paymentEligible()).isTrue();
            assertThat(fixture.queries.findAccessible(SELLER, first).id()).isEqualTo(first);
            assertThatThrownBy(() -> fixture.queries.findAccessible(9999L, first))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(TradeErrorCode.ORDER_FORBIDDEN));
        });
    }

    private static void withDatabase(ThrowingConsumer<Fixture> test) throws Exception {
        String host = environmentOrDefault("TIDEBID_MYSQL_HOST", "127.0.0.1");
        String port = environmentOrDefault("TIDEBID_MYSQL_PORT", "13306");
        String password = System.getenv("TIDEBID_MYSQL_ROOT_PASSWORD");
        String schema = "tidebid_trade_pay_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toLowerCase(Locale.ROOT);
        String serverUrl = jdbcUrl(host, port, "");
        String schemaUrl = jdbcUrl(host, port, schema);
        try (Connection admin = DriverManager.getConnection(serverUrl, "root", password);
             Statement statement = admin.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + schema
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                Flyway.configure().dataSource(schemaUrl, "root", password).locations("classpath:db/migration")
                        .defaultSchema(schema).cleanDisabled(true).load().migrate();
                test.accept(new Fixture(schemaUrl, password));
            } finally {
                statement.executeUpdate("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private static final class Fixture {
        private final JdbcTemplate jdbc;
        private final TradePaymentTransaction payments;
        private final TransactionTemplate transactions;
        private final TradeOrderQueryService queries;
        private final AtomicLong ids = new AtomicLong(3000000000000000000L);

        private Fixture(String url, String password) {
            var dataSource = new DriverManagerDataSource(url, "root", password);
            jdbc = new JdbcTemplate(dataSource);
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            var outbox = new JdbcTradeOutboxRepository(dataSource, new TradeOutboxProperties(
                    Duration.ofSeconds(1), 10, Duration.ofSeconds(30), Duration.ofSeconds(1),
                    Duration.ofSeconds(8), 5, Duration.ofHours(48)));
            payments = new TradePaymentTransaction(dataSource, ids::incrementAndGet, outbox,
                    new TradeOutboxEventFactory(new ObjectMapper().findAndRegisterModules()),
                    new TradePaymentProperties(Duration.ofSeconds(5)), clock);
            transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            queries = new TradeOrderQueryService(dataSource, clock);
        }

        private long seedOrder(long auctionId) {
            long id = ids.incrementAndGet();
            jdbc.update("""
                    INSERT INTO trade_order (
                        id, order_no, auction_id, item_id, winning_bid_id, seller_id, buyer_id,
                        item_title, winner_hold_no, winner_hold_amount, final_price,
                        captured_deposit_amount, payable_amount, status, payment_deadline,
                        seller_settlement_status, version, auction_closed_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 50.00, 150.00, 50.00, 100.00,
                              'PENDING_PAYMENT', ?, 'NOT_REQUIRED', 1, ?, ?, ?)
                    """, id, "TB-" + auctionId, auctionId, auctionId + 1000, auctionId + 2000,
                    SELLER, BUYER, "拍品-" + auctionId, "hold-" + auctionId,
                    Timestamp.from(NOW.plusSeconds(1800)), Timestamp.from(NOW.minusSeconds(120)),
                    Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW.minusSeconds(60)));
            return id;
        }

        private TradePaymentTransaction.StartResult begin(long buyerId, long orderId, String requestId) {
            return transactions.execute(status -> payments.begin(buyerId, orderId, requestId));
        }

        private PaymentAttemptSnapshot apply(long attemptId, AccountDebitPort.DebitResult result) {
            return transactions.execute(status -> payments.apply(attemptId, result, "trace-payment-123"));
        }

        private Object tryBegin(CountDownLatch start, long orderId, String requestId) throws InterruptedException {
            start.await();
            try {
                return begin(BUYER, orderId, requestId);
            } catch (BusinessException exception) {
                return exception;
            }
        }

        private String orderStatus(long orderId) {
            return jdbc.queryForObject("SELECT status FROM trade_order WHERE id = ?", String.class, orderId);
        }

        private long attemptCount() {
            return jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class);
        }

        private long attemptCount(long orderId) {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM payment_attempt WHERE order_id = ?", Long.class, orderId);
        }

        private long outboxCount(long orderId, String eventType) {
            return jdbc.queryForObject("""
                    SELECT COUNT(*) FROM trade_outbox WHERE aggregate_id = ? AND event_type = ?
                    """, Long.class, Long.toString(orderId), eventType);
        }
    }

    private static BigDecimal money(String value) { return new BigDecimal(value); }

    private static String jdbcUrl(String host, String port, String schema) {
        return "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC"
                + "&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> { void accept(T value) throws Exception; }
}

package io.github.carpl2.tidebid.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.account.application.port.AccountRegistrationStore;
import io.github.carpl2.tidebid.account.infrastructure.config.AccountRocketMqProperties;
import io.github.carpl2.tidebid.account.infrastructure.messaging.AccountOutboxEventFactory;
import io.github.carpl2.tidebid.account.infrastructure.messaging.AccountRocketMqTransport;
import io.github.carpl2.tidebid.account.infrastructure.messaging.AccountSellerCreditHandler;
import io.github.carpl2.tidebid.account.infrastructure.messaging.JdbcAccountOutboxRepository;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserRoleMapper;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.SellerCreditReason;
import io.github.carpl2.tidebid.contracts.SellerCreditRequestedEvent;
import io.github.carpl2.tidebid.contracts.SellerCreditedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local-db")
@Import(AccountJwtTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TIDEBID_ACCOUNT_DB_PASSWORD", matches = ".+")
class SellerCreditIntegrationTest {

    @Autowired private AccountSellerCreditHandler handler;
    @Autowired private AccountRegistrationStore registrationStore;
    @Autowired private AccountRocketMqProperties messaging;
    @Autowired private AccountOutboxEventFactory eventFactory;
    @Autowired private JdbcAccountOutboxRepository outbox;
    @Autowired private UserAccountMapper userMapper;
    @Autowired private UserRoleMapper roleMapper;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void defaultCompensationCreditsOnlyTheCapturedDeposit() throws Exception {
        TestSeller seller = createSeller("100.00");
        UUID eventId = UUID.randomUUID();
        long orderId = Math.abs(eventId.getMostSignificantBits() % 1_000_000_000L) + 1000L;
        Instant terminalAt = Instant.now().minusSeconds(1);
        SellerCreditRequestedEvent request = new SellerCreditRequestedEvent(
                SellerCreditReason.DEFAULT_COMPENSATION, "SC:" + orderId + ":DEFAULT",
                orderId, "TB" + orderId, orderId + 1, seller.userId(),
                new BigDecimal("2333.00"), new BigDecimal("1000.00"),
                new BigDecimal("1000.00"), terminalAt, terminalAt.plusMillis(1));
        try {
            handler.handle(message(eventId, request));

            assertThat(balance(seller.userId())).isEqualByComparingTo("1100.00");
            Map<String, Object> credit = jdbc.queryForMap(
                    "SELECT credit_reason, amount FROM wallet_credit WHERE credit_no = ?",
                    request.creditNo());
            assertThat(credit.get("credit_reason")).isEqualTo("DEFAULT_COMPENSATION");
            assertThat((BigDecimal) credit.get("amount")).isEqualByComparingTo("1000.00");
        } finally {
            deleteSeller(seller, request.creditNo(), eventId);
        }
    }

    @Test
    void duplicateAndConcurrentRequestsCreditSellerExactlyOnce() throws Exception {
        TestSeller seller = createSeller("100.00");
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        SellerCreditRequestedEvent request = request(seller.userId(), "2333.00", firstId);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> consumeTogether(ready, start, firstId, request));
                var second = executor.submit(() -> consumeTogether(ready, start, secondId, request));
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);
            }
            handler.handle(message(firstId, request));

            assertThat(balance(seller.userId())).isEqualByComparingTo("2433.00");
            assertThat(count("wallet_credit", "credit_no", request.creditNo())).isOne();
            assertThat(count("wallet_ledger", "business_no", request.creditNo())).isOne();
            assertThat(count("account_outbox", "aggregate_id", request.creditNo())).isOne();
            assertThat(inboxCount(firstId) + inboxCount(secondId)).isEqualTo(2);
        } finally {
            deleteSeller(seller, request.creditNo(), firstId, secondId);
        }
    }

    @Test
    void conflictingReplayRollsBackInboxAndMoney() throws Exception {
        TestSeller seller = createSeller("100.00");
        UUID firstId = UUID.randomUUID();
        UUID conflictId = UUID.randomUUID();
        SellerCreditRequestedEvent original = request(seller.userId(), "2333.00", firstId);
        SellerCreditRequestedEvent conflict = new SellerCreditRequestedEvent(
                SellerCreditReason.SALE_PROCEEDS, original.creditNo(), original.orderId(),
                original.orderNo(), original.auctionId(), original.sellerId(), new BigDecimal("2400.00"),
                original.capturedDepositAmount(), new BigDecimal("2400.00"),
                original.orderTerminalAt(), original.requestedAt());
        try {
            handler.handle(message(firstId, original));
            assertThatThrownBy(() -> handler.handle(message(conflictId, conflict)))
                    .hasMessageContaining("different intent");

            assertThat(balance(seller.userId())).isEqualByComparingTo("2433.00");
            assertThat(inboxCount(conflictId)).isZero();
            assertThat(count("wallet_credit", "credit_no", original.creditNo())).isOne();
        } finally {
            deleteSeller(seller, original.creditNo(), firstId, conflictId);
        }
    }

    @Test
    void outboxCollisionRollsBackWalletCreditLedgerAndInbox() throws Exception {
        TestSeller seller = createSeller("100.00");
        UUID sourceId = UUID.randomUUID();
        SellerCreditRequestedEvent request = request(seller.userId(), "1000.00", sourceId);
        Instant collisionAt = Instant.now();
        SellerCreditedEvent collision = new SellerCreditedEvent(
                request.creditReason(), request.creditNo(), request.orderId(), request.orderNo(),
                request.auctionId(), request.sellerId(), request.creditAmount(), collisionAt);
        outbox.enqueue(eventFactory.sellerCredited(collision, "seller-credit-test"), collisionAt);
        try {
            assertThatThrownBy(() -> handler.handle(message(sourceId, request)))
                    .isInstanceOf(RuntimeException.class);

            assertThat(balance(seller.userId())).isEqualByComparingTo("100.00");
            assertThat(inboxCount(sourceId)).isZero();
            assertThat(count("wallet_credit", "credit_no", request.creditNo())).isZero();
            assertThat(count("wallet_ledger", "business_no", request.creditNo())).isZero();
        } finally {
            deleteSeller(seller, request.creditNo(), sourceId);
        }
    }

    private Void consumeTogether(
            CountDownLatch ready,
            CountDownLatch start,
            UUID eventId,
            SellerCreditRequestedEvent request
    ) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent seller-credit start timed out");
        }
        handler.handle(message(eventId, request));
        return null;
    }

    private TestSeller createSeller(String balance) {
        long userId = registrationStore.create(new AccountRegistrationStore.RegistrationData(
                "sc" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                "$2a$12$seller.credit.integration.placeholder", "Seller credit integration",
                new BigDecimal(balance), BigDecimal.ZERO));
        long walletId = jdbc.queryForObject(
                "SELECT id FROM wallet_account WHERE user_id = ?", Long.class, userId);
        return new TestSeller(userId, walletId);
    }

    private SellerCreditRequestedEvent request(long sellerId, String amount, UUID seed) {
        long orderId = Math.abs(seed.getMostSignificantBits() % 1_000_000_000L) + 1000L;
        long auctionId = Math.abs(seed.getLeastSignificantBits() % 1_000_000_000L) + 1000L;
        Instant terminalAt = Instant.now().minusSeconds(1);
        return new SellerCreditRequestedEvent(
                SellerCreditReason.SALE_PROCEEDS, "SC:" + orderId + ":SALE", orderId,
                "TB" + orderId, auctionId, sellerId, new BigDecimal(amount), new BigDecimal("1000.00"),
                new BigDecimal(amount), terminalAt, terminalAt.plusMillis(1));
    }

    private AccountRocketMqTransport.InboundMessage message(
            UUID eventId,
            SellerCreditRequestedEvent request
    ) throws Exception {
        EventEnvelope<SellerCreditRequestedEvent> envelope = new EventEnvelope<>(
                eventId, SellerCreditRequestedEvent.EVENT_TYPE, SellerCreditRequestedEvent.SCHEMA_VERSION,
                request.requestedAt(), "tidebid-trade-service", "seller-credit-test", request);
        byte[] body = objectMapper.writeValueAsBytes(envelope);
        return new AccountRocketMqTransport.InboundMessage(
                eventId.toString(), messaging.topics().tradeEvents(),
                SellerCreditRequestedEvent.EVENT_TYPE, List.of(eventId.toString()),
                Map.of(
                        "eventId", eventId.toString(),
                        "eventType", SellerCreditRequestedEvent.EVENT_TYPE,
                        "schemaVersion", Integer.toString(SellerCreditRequestedEvent.SCHEMA_VERSION),
                        "payloadHash", sha256(body)), body, 1);
    }

    private BigDecimal balance(long userId) {
        return jdbc.queryForObject(
                "SELECT available_balance FROM wallet_account WHERE user_id = ?", BigDecimal.class, userId);
    }

    private int inboxCount(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM account_inbox WHERE consumer_name = ? AND event_id = ?
                """, Integer.class, messaging.consumerGroups().sellerCredit(), eventId.toString());
    }

    private int count(String table, String column, String value) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
    }

    private void deleteSeller(TestSeller seller, String creditNo, UUID... eventIds) {
        jdbc.update("DELETE FROM account_outbox WHERE aggregate_id = ?", creditNo);
        for (UUID eventId : eventIds) {
            jdbc.update("DELETE FROM account_inbox WHERE consumer_name = ? AND event_id = ?",
                    messaging.consumerGroups().sellerCredit(), eventId.toString());
        }
        jdbc.update("DELETE FROM wallet_ledger WHERE wallet_id = ?", seller.walletId());
        jdbc.update("DELETE FROM wallet_credit WHERE seller_id = ?", seller.userId());
        jdbc.update("DELETE FROM wallet_account WHERE id = ?", seller.walletId());
        roleMapper.selectByUserId(seller.userId()).forEach(role ->
                roleMapper.delete(seller.userId(), role.getRoleCode()));
        userMapper.deleteById(seller.userId());
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record TestSeller(long userId, long walletId) { }
}

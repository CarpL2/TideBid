package io.github.carpl2.tidebid.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.account.application.HoldWalletFundsCommand;
import io.github.carpl2.tidebid.account.application.WalletHoldService;
import io.github.carpl2.tidebid.account.application.port.AccountRegistrationStore;
import io.github.carpl2.tidebid.account.domain.WalletHoldBusinessType;
import io.github.carpl2.tidebid.account.infrastructure.config.AccountRocketMqProperties;
import io.github.carpl2.tidebid.account.infrastructure.messaging.AccountDepositSettlementHandler;
import io.github.carpl2.tidebid.account.infrastructure.messaging.AccountOutboxEventFactory;
import io.github.carpl2.tidebid.account.infrastructure.messaging.AccountRocketMqTransport;
import io.github.carpl2.tidebid.account.infrastructure.messaging.JdbcAccountOutboxRepository;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserRoleMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletAccountMapper;
import io.github.carpl2.tidebid.contracts.DepositSettlementRequestedEvent;
import io.github.carpl2.tidebid.contracts.DepositSettlementType;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.WalletHoldSettledEvent;
import io.github.carpl2.tidebid.contracts.WalletHoldSettlementStatus;
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
class DepositReleaseIntegrationTest {

    @Autowired private AccountDepositSettlementHandler handler;
    @Autowired private WalletHoldService holdService;
    @Autowired private AccountRegistrationStore registrationStore;
    @Autowired private WalletAccountMapper walletMapper;
    @Autowired private UserAccountMapper userMapper;
    @Autowired private UserRoleMapper roleMapper;
    @Autowired private AccountRocketMqProperties messaging;
    @Autowired private AccountOutboxEventFactory eventFactory;
    @Autowired private JdbcAccountOutboxRepository outbox;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void releaseCommitsHoldWalletLedgerInboxAndOutboxExactlyOnce() throws Exception {
        TestAccount account = createHeldAccount("1000.00", "250.00");
        UUID eventId = UUID.randomUUID();
        try {
            AccountRocketMqTransport.InboundMessage sameMessage = message(eventId, account);
            handler.handle(sameMessage);
            handler.handle(sameMessage);

            assertReleased(account, "1000.00", "0.00");
            assertThat(count("account_inbox", "event_id", eventId.toString())).isEqualTo(1);
            assertThat(count("account_outbox", "aggregate_id", account.holdNo())).isEqualTo(1);
            String resultEventId = AccountOutboxEventFactory.deterministicSettlementResultEventId(
                    account.holdNo(), DepositSettlementType.RELEASE.name()).toString();
            assertThat(count("wallet_ledger", "business_no", resultEventId)).isEqualTo(1);

            UUID conflictingId = UUID.randomUUID();
            assertThatThrownBy(() -> handler.handle(message(conflictingId, account, 90002L)))
                    .hasMessageContaining("different intent");
            assertThat(count("account_inbox", "event_id", conflictingId.toString())).isZero();
        } finally {
            deleteAccount(account, eventId);
        }
    }

    @Test
    void concurrentDifferentReleaseEventsOnlyMoveMoneyOnce() throws Exception {
        TestAccount account = createHeldAccount("1000.00", "300.00");
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                consumeTogether(ready, start, firstId, account);
                return null;
            });
            var second = executor.submit(() -> {
                consumeTogether(ready, start, secondId, account);
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

            assertReleased(account, "1000.00", "0.00");
            assertThat(count("account_inbox", "event_id", firstId.toString())
                    + count("account_inbox", "event_id", secondId.toString())).isEqualTo(2);
            assertThat(count("account_outbox", "aggregate_id", account.holdNo())).isEqualTo(1);
        } finally {
            deleteAccount(account, firstId, secondId);
        }
    }

    @Test
    void outboxCollisionRollsBackInboxHoldWalletAndReleaseLedger() throws Exception {
        TestAccount account = createHeldAccount("1000.00", "225.00");
        UUID sourceEventId = UUID.randomUUID();
        Instant collisionTime = Instant.now();
        WalletHoldSettledEvent collision = new WalletHoldSettledEvent(
                DepositSettlementType.RELEASE, WalletHoldSettlementStatus.RELEASED,
                90001L, null, account.userId(), account.holdNo(), account.amount(),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), account.amount(), collisionTime);
        outbox.enqueue(eventFactory.walletHoldSettled(collision, "release-test-trace"), collisionTime);
        try {
            assertThatThrownBy(() -> handler.handle(message(sourceEventId, account)))
                    .isInstanceOf(RuntimeException.class);

            WalletAccountEntity wallet = walletMapper.selectByUserId(account.userId());
            assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("775.00");
            assertThat(wallet.getFrozenBalance()).isEqualByComparingTo("225.00");
            Map<String, Object> hold = jdbc.queryForMap(
                    "SELECT status, settlement_event_id FROM wallet_hold WHERE hold_no = ?", account.holdNo());
            assertThat(hold.get("status")).isEqualTo("HELD");
            assertThat(hold.get("settlement_event_id")).isNull();
            assertThat(count("account_inbox", "event_id", sourceEventId.toString())).isZero();
            String resultEventId = AccountOutboxEventFactory.deterministicSettlementResultEventId(
                    account.holdNo(), DepositSettlementType.RELEASE.name()).toString();
            assertThat(count("wallet_ledger", "business_no", resultEventId)).isZero();
        } finally {
            deleteAccount(account, sourceEventId);
        }
    }

    private void consumeTogether(
            CountDownLatch ready,
            CountDownLatch start,
            UUID eventId,
            TestAccount account
    ) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent release start timed out");
        }
        handler.handle(message(eventId, account));
    }

    private TestAccount createHeldAccount(String balance, String holdAmount) {
        long userId = registrationStore.create(new AccountRegistrationStore.RegistrationData(
                "dr" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                "$2a$12$deposit.release.integration.placeholder", "Deposit release integration",
                new BigDecimal(balance), BigDecimal.ZERO));
        WalletAccountEntity wallet = walletMapper.selectByUserId(userId);
        String holdNo = "REGISTRATION:" + UUID.randomUUID().toString().replace("-", "");
        holdService.hold(new HoldWalletFundsCommand(
                holdNo, userId, WalletHoldBusinessType.AUCTION_DEPOSIT, new BigDecimal(holdAmount)));
        return new TestAccount(userId, wallet.getId(), holdNo, new BigDecimal(holdAmount));
    }

    private AccountRocketMqTransport.InboundMessage message(UUID eventId, TestAccount account) throws Exception {
        return message(eventId, account, 90001L);
    }

    private AccountRocketMqTransport.InboundMessage message(
            UUID eventId,
            TestAccount account,
            long auctionId
    ) throws Exception {
        DepositSettlementRequestedEvent payload = new DepositSettlementRequestedEvent(
                DepositSettlementType.RELEASE, auctionId, null, account.userId(), account.holdNo(),
                account.amount(), BigDecimal.ZERO.setScale(2));
        EventEnvelope<DepositSettlementRequestedEvent> envelope = new EventEnvelope<>(
                eventId, DepositSettlementRequestedEvent.EVENT_TYPE,
                DepositSettlementRequestedEvent.SCHEMA_VERSION, Instant.now(),
                "tidebid-auction-service", "release-test-trace", payload);
        byte[] body = objectMapper.writeValueAsBytes(envelope);
        return new AccountRocketMqTransport.InboundMessage(
                eventId.toString(), messaging.topics().auctionEvents(),
                DepositSettlementRequestedEvent.EVENT_TYPE, List.of(eventId.toString()),
                Map.of(
                        "eventId", eventId.toString(),
                        "eventType", DepositSettlementRequestedEvent.EVENT_TYPE,
                        "schemaVersion", Integer.toString(DepositSettlementRequestedEvent.SCHEMA_VERSION),
                        "payloadHash", sha256(body)), body, 1);
    }

    private void assertReleased(TestAccount account, String available, String frozen) {
        WalletAccountEntity wallet = walletMapper.selectByUserId(account.userId());
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo(available);
        assertThat(wallet.getFrozenBalance()).isEqualByComparingTo(frozen);
        Map<String, Object> hold = jdbc.queryForMap(
                "SELECT status, captured_amount, released_amount, settlement_event_id FROM wallet_hold WHERE hold_no = ?",
                account.holdNo());
        assertThat(hold.get("status")).isEqualTo("RELEASED");
        assertThat((BigDecimal) hold.get("captured_amount")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) hold.get("released_amount")).isEqualByComparingTo(account.amount());
        assertThat(hold.get("settlement_event_id")).isNotNull();
    }

    private int count(String table, String column, String value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
    }

    private void deleteAccount(TestAccount account, UUID... eventIds) {
        jdbc.update("DELETE FROM account_outbox WHERE aggregate_id = ?", account.holdNo());
        for (UUID eventId : eventIds) {
            jdbc.update("DELETE FROM account_inbox WHERE consumer_name = ? AND event_id = ?",
                    messaging.consumerGroups().depositSettlement(), eventId.toString());
        }
        jdbc.update("DELETE FROM wallet_ledger WHERE wallet_id = ?", account.walletId());
        jdbc.update("DELETE FROM wallet_hold WHERE user_id = ?", account.userId());
        jdbc.update("DELETE FROM wallet_account WHERE id = ?", account.walletId());
        roleMapper.selectByUserId(account.userId()).forEach(role ->
                roleMapper.delete(account.userId(), role.getRoleCode()));
        userMapper.deleteById(account.userId());
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record TestAccount(long userId, long walletId, String holdNo, BigDecimal amount) { }
}

package io.github.carpl2.tidebid.account;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.carpl2.tidebid.account.application.CreateWalletDebitCommand;
import io.github.carpl2.tidebid.account.application.WalletDebitService;
import io.github.carpl2.tidebid.account.application.port.AccountRegistrationStore;
import io.github.carpl2.tidebid.account.domain.WalletDebit;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserRoleEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletLedgerEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserRoleMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletLedgerMapper;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local-db")
@Import(AccountJwtTestConfiguration.class)
@TestPropertySource(properties = "tidebid.internal-service.token="
        + InternalWalletDebitApiIntegrationTest.TEST_TOKEN)
@EnabledIfEnvironmentVariable(named = "TIDEBID_ACCOUNT_DB_PASSWORD", matches = ".+")
class InternalWalletDebitApiIntegrationTest {

    static final String TEST_TOKEN = "wallet-debit-integration-token-123456";

    @Autowired
    private TestRestTemplate client;
    @Autowired
    private WalletDebitService service;
    @Autowired
    private AccountRegistrationStore registrationStore;
    @Autowired
    private UserAccountMapper userAccountMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private WalletAccountMapper walletAccountMapper;
    @Autowired
    private WalletLedgerMapper walletLedgerMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void authorizedCreateReplayAndQueryDebitExactlyOnce() {
        TestAccount account = createAccount("1000.00");
        String paymentNo = paymentNo();
        String traceId = "wallet-debit-trace-1234";
        try {
            ResponseEntity<JsonNode> created = post(TEST_TOKEN, "wallet-debit-request-1",
                    request(paymentNo, account.userId(), 9001L, "250.00"), traceId);
            ResponseEntity<JsonNode> repeated = post(TEST_TOKEN, "wallet-debit-request-2",
                    request(paymentNo, account.userId(), 9001L, "250.0"), traceId);
            ResponseEntity<JsonNode> queried = get(TEST_TOKEN, paymentNo, traceId);

            assertDebit(created, paymentNo, account.userId(), 9001L, "250.00", "SUCCEEDED", null, traceId);
            assertDebit(repeated, paymentNo, account.userId(), 9001L, "250.00", "SUCCEEDED", null, traceId);
            assertDebit(queried, paymentNo, account.userId(), 9001L, "250.00", "SUCCEEDED", null, traceId);
            assertThat(created.getBody().path("data").path("debitId").asText())
                    .isEqualTo(repeated.getBody().path("data").path("debitId").asText())
                    .isEqualTo(queried.getBody().path("data").path("debitId").asText());
            assertWallet(account.userId(), "750.00", 1L);
            assertThat(countDebit(paymentNo)).isEqualTo(1);
            assertThat(findLedgers(paymentNo)).hasSize(1);
        } finally {
            deleteAccount(account.userId());
        }
    }

    @Test
    void insufficientBalanceIsAStableRejectedResult() {
        TestAccount account = createAccount("100.00");
        String paymentNo = paymentNo();
        try {
            ResponseEntity<JsonNode> rejected = post(TEST_TOKEN, "wallet-debit-reject-1",
                    request(paymentNo, account.userId(), 9002L, "100.01"), null);
            assertDebit(rejected, paymentNo, account.userId(), 9002L, "100.01",
                    "REJECTED", WalletDebit.INSUFFICIENT_BALANCE, null);

            jdbc.update("UPDATE wallet_account SET available_balance = 1000.00 WHERE user_id = ?",
                    account.userId());
            ResponseEntity<JsonNode> retried = post(TEST_TOKEN, "wallet-debit-reject-2",
                    request(paymentNo, account.userId(), 9002L, "100.01"), null);
            assertDebit(retried, paymentNo, account.userId(), 9002L, "100.01",
                    "REJECTED", WalletDebit.INSUFFICIENT_BALANCE, null);
            assertWallet(account.userId(), "1000.00", 0L);
            assertThat(countDebit(paymentNo)).isEqualTo(1);
            assertThat(findLedgers(paymentNo)).isEmpty();
        } finally {
            deleteAccount(account.userId());
        }
    }

    @Test
    void authenticationValidationConflictAndMissingResultAreStable() {
        TestAccount account = createAccount("500.00");
        String paymentNo = paymentNo();
        try {
            assertFailure(post(null, "wallet-debit-auth-1",
                    request(paymentNo, account.userId(), 9003L, "10.00"), null),
                    401, "COMMON_UNAUTHENTICATED");
            assertFailure(post("wrong-token", "wallet-debit-auth-2",
                    request(paymentNo, account.userId(), 9003L, "10.00"), null),
                    403, "COMMON_FORBIDDEN");
            assertFailure(post(TEST_TOKEN, null,
                    request(paymentNo(), account.userId(), 9003L, "10.00"), null),
                    400, "COMMON_INVALID_ARGUMENT");

            assertDebit(post(TEST_TOKEN, "wallet-debit-conflict-1",
                            request(paymentNo, account.userId(), 9003L, "10.00"), null),
                    paymentNo, account.userId(), 9003L, "10.00", "SUCCEEDED", null, null);
            assertFailure(post(TEST_TOKEN, "wallet-debit-conflict-2",
                    request(paymentNo, account.userId(), 9004L, "10.00"), null),
                    409, "ACCOUNT_WALLET_DEBIT_IDEMPOTENCY_CONFLICT");
            assertFailure(get(TEST_TOKEN, paymentNo(), null),
                    404, "ACCOUNT_WALLET_DEBIT_NOT_FOUND");
            assertFailure(post(TEST_TOKEN, "wallet-debit-invalid",
                    request(paymentNo(), account.userId(), 9005L, "1.001"), null),
                    400, "COMMON_INVALID_ARGUMENT");
            assertThat(countDebit(paymentNo)).isEqualTo(1);
            assertWallet(account.userId(), "490.00", 1L);
        } finally {
            deleteAccount(account.userId());
        }
    }

    @Test
    void disabledAccountAndMissingWalletNeverCreateAResult() {
        TestAccount disabled = createAccount("100.00");
        TestAccount withoutWallet = createAccount("100.00");
        String disabledPayment = paymentNo();
        String missingWalletPayment = paymentNo();
        try {
            jdbc.update("UPDATE user_account SET status = 'DISABLED' WHERE id = ?", disabled.userId());
            assertFailure(post(TEST_TOKEN, "wallet-debit-disabled",
                    request(disabledPayment, disabled.userId(), 9006L, "10.00"), null),
                    403, "ACCOUNT_DISABLED");

            walletLedgerMapper.delete(new LambdaQueryWrapper<WalletLedgerEntity>()
                    .eq(WalletLedgerEntity::getWalletId, withoutWallet.walletId()));
            walletAccountMapper.deleteById(withoutWallet.walletId());
            assertFailure(post(TEST_TOKEN, "wallet-debit-no-wallet",
                    request(missingWalletPayment, withoutWallet.userId(), 9007L, "10.00"), null),
                    404, "ACCOUNT_WALLET_NOT_FOUND");
            assertThat(countDebit(disabledPayment)).isZero();
            assertThat(countDebit(missingWalletPayment)).isZero();
        } finally {
            deleteAccount(disabled.userId());
            deleteAccount(withoutWallet.userId());
        }
    }

    @Test
    void concurrentReplayDebitsAndWritesLedgerOnce() throws Exception {
        TestAccount account = createAccount("1000.00");
        String paymentNo = paymentNo();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<WalletDebit> task = () -> {
                ready.countDown();
                start.await();
                return service.debit(new CreateWalletDebitCommand(
                        paymentNo, account.userId(), 9008L, new BigDecimal("300.00")));
            };
            Future<WalletDebit> first = executor.submit(task);
            Future<WalletDebit> second = executor.submit(task);
            ready.await();
            start.countDown();

            assertThat(first.get().id()).isEqualTo(second.get().id());
            assertWallet(account.userId(), "700.00", 1L);
            assertThat(countDebit(paymentNo)).isEqualTo(1);
            assertThat(findLedgers(paymentNo)).hasSize(1);
        } finally {
            executor.shutdownNow();
            deleteAccount(account.userId());
        }
    }

    @Test
    void ledgerFailureRollsBackWalletAndDebitResult() {
        TestAccount account = createAccount("1000.00");
        String paymentNo = paymentNo();
        try {
            WalletLedgerEntity conflict = new WalletLedgerEntity();
            conflict.setWalletId(account.walletId());
            conflict.setBusinessNo(paymentNo);
            conflict.setLedgerType("ORDER_PAYMENT");
            conflict.setAvailableDelta(BigDecimal.ZERO.setScale(2));
            conflict.setFrozenDelta(BigDecimal.ZERO.setScale(2));
            conflict.setAvailableBalanceAfter(new BigDecimal("1000.00"));
            conflict.setFrozenBalanceAfter(BigDecimal.ZERO.setScale(2));
            walletLedgerMapper.insert(conflict);

            assertThatThrownBy(() -> service.debit(new CreateWalletDebitCommand(
                    paymentNo, account.userId(), 9009L, new BigDecimal("125.00"))))
                    .isInstanceOf(DuplicateKeyException.class);
            assertWallet(account.userId(), "1000.00", 0L);
            assertThat(countDebit(paymentNo)).isZero();
            assertThat(findLedgers(paymentNo)).hasSize(1);
        } finally {
            deleteAccount(account.userId());
        }
    }

    private ResponseEntity<JsonNode> post(
            String token,
            String requestId,
            Map<String, Object> body,
            String traceId
    ) {
        HttpHeaders headers = headers(token, traceId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (requestId != null) {
            headers.set(SecurityHeaders.REQUEST_ID, requestId);
        }
        return client.postForEntity("/internal/wallet-debits", new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String token, String paymentNo, String traceId) {
        return client.exchange("/internal/wallet-debits/" + paymentNo, HttpMethod.GET,
                new HttpEntity<>(headers(token, traceId)), JsonNode.class);
    }

    private static HttpHeaders headers(String token, String traceId) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set(SecurityHeaders.INTERNAL_SERVICE_TOKEN, token);
        }
        if (traceId != null) {
            headers.set(SecurityHeaders.TRACE_ID, traceId);
        }
        return headers;
    }

    private static Map<String, Object> request(String paymentNo, long userId, long orderId, String amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentNo", paymentNo);
        body.put("userId", Long.toString(userId));
        body.put("orderId", Long.toString(orderId));
        body.put("amount", new BigDecimal(amount));
        return body;
    }

    private TestAccount createAccount(String balance) {
        String username = "wd" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long userId = registrationStore.create(new AccountRegistrationStore.RegistrationData(
                username, "$2a$12$wallet.debit.integration.placeholder", "Wallet debit integration",
                new BigDecimal(balance), BigDecimal.ZERO));
        return new TestAccount(userId, walletAccountMapper.selectByUserId(userId).getId());
    }

    private void assertWallet(long userId, String available, long version) {
        WalletAccountEntity wallet = walletAccountMapper.selectByUserId(userId);
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo(available);
        assertThat(wallet.getFrozenBalance()).isEqualByComparingTo("0.00");
        assertThat(wallet.getVersion()).isEqualTo(version);
    }

    private int countDebit(String paymentNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM wallet_debit WHERE payment_no = ?",
                Integer.class, paymentNo);
    }

    private List<WalletLedgerEntity> findLedgers(String businessNo) {
        return walletLedgerMapper.selectList(new LambdaQueryWrapper<WalletLedgerEntity>()
                .eq(WalletLedgerEntity::getBusinessNo, businessNo));
    }

    private void deleteAccount(long userId) {
        WalletAccountEntity wallet = walletAccountMapper.selectByUserId(userId);
        jdbc.update("DELETE FROM wallet_debit WHERE user_id = ?", userId);
        if (wallet != null) {
            walletLedgerMapper.delete(new LambdaQueryWrapper<WalletLedgerEntity>()
                    .eq(WalletLedgerEntity::getWalletId, wallet.getId()));
            walletAccountMapper.deleteById(wallet.getId());
        }
        List<UserRoleEntity> roles = new ArrayList<>(userRoleMapper.selectByUserId(userId));
        for (UserRoleEntity role : roles) {
            userRoleMapper.delete(userId, role.getRoleCode());
        }
        userAccountMapper.deleteById(userId);
    }

    private static void assertDebit(
            ResponseEntity<JsonNode> response,
            String paymentNo,
            long userId,
            long orderId,
            String amount,
            String status,
            String failureCode,
            String traceId
    ) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("SUCCESS");
        JsonNode data = response.getBody().path("data");
        assertThat(data.path("debitId").isTextual()).isTrue();
        assertThat(data.path("paymentNo").asText()).isEqualTo(paymentNo);
        assertThat(data.path("userId").asText()).isEqualTo(Long.toString(userId));
        assertThat(data.path("orderId").asText()).isEqualTo(Long.toString(orderId));
        assertThat(data.path("amount").decimalValue()).isEqualByComparingTo(amount);
        assertThat(data.path("status").asText()).isEqualTo(status);
        if (failureCode == null) {
            assertThat(data.path("failureCode").isNull()).isTrue();
        } else {
            assertThat(data.path("failureCode").asText()).isEqualTo(failureCode);
        }
        assertThat(data.path("decidedAt").asText()).endsWith("Z");
        if (traceId != null) {
            assertThat(response.getHeaders().getFirst(SecurityHeaders.TRACE_ID)).isEqualTo(traceId);
            assertThat(response.getBody().path("traceId").asText()).isEqualTo(traceId);
        }
        assertThat(response.getBody().toString()).doesNotContain(TEST_TOKEN);
    }

    private static void assertFailure(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
        assertThat(response.getBody().toString()).doesNotContain(TEST_TOKEN);
    }

    private static String paymentNo() {
        return "PAY:" + UUID.randomUUID().toString().replace("-", "");
    }

    private record TestAccount(long userId, long walletId) {
    }
}

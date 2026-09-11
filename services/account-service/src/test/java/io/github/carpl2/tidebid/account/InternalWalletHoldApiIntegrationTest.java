package io.github.carpl2.tidebid.account;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.carpl2.tidebid.account.application.port.AccountRegistrationStore;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserRoleEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletHoldEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletLedgerEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserRoleMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletHoldMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletLedgerMapper;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local-db")
@Import(AccountJwtTestConfiguration.class)
@TestPropertySource(properties = "tidebid.internal-service.token="
        + InternalWalletHoldApiIntegrationTest.TEST_TOKEN)
@EnabledIfEnvironmentVariable(named = "TIDEBID_ACCOUNT_DB_PASSWORD", matches = ".+")
class InternalWalletHoldApiIntegrationTest {

    static final String TEST_TOKEN = "wallet-api-integration-token-123456789";

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private AccountRegistrationStore registrationStore;

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private WalletAccountMapper walletAccountMapper;

    @Autowired
    private WalletHoldMapper walletHoldMapper;

    @Autowired
    private WalletLedgerMapper walletLedgerMapper;

    @Test
    void missingAndWrongInternalTokensAreRejectedBeforeAnyWalletWrite() {
        TestAccount account = createAccount("1000.00");
        String holdNo = uniqueHoldNo();
        try {
            ResponseEntity<JsonNode> missing = postHold(
                    null,
                    "internal-auth-request-1",
                    holdNo,
                    account.userId(),
                    "100.00",
                    null
            );
            ResponseEntity<JsonNode> wrong = postHold(
                    "wrong-internal-token-never-authorized",
                    "internal-auth-request-2",
                    holdNo,
                    account.userId(),
                    "100.00",
                    null
            );

            assertFailure(missing, 401, "COMMON_UNAUTHENTICATED");
            assertFailure(wrong, 403, "COMMON_FORBIDDEN");
            assertThat(missing.getBody().toString()).doesNotContain(TEST_TOKEN);
            assertThat(wrong.getBody().toString()).doesNotContain(TEST_TOKEN);
            assertWallet(account.userId(), "1000.00", "0.00", 0L);
            assertThat(findHolds(account.userId())).isEmpty();
        } finally {
            deleteAccount(account.userId());
        }
    }

    @Test
    void authorizedPostAndGetReturnStableContractAndPreserveTraceId() {
        TestAccount account = createAccount("1000.00");
        String holdNo = uniqueHoldNo();
        String traceId = "internal-wallet-trace-1234";
        try {
            ResponseEntity<JsonNode> created = postHold(
                    TEST_TOKEN,
                    "wallet-hold-request-1",
                    holdNo,
                    account.userId(),
                    "250.00",
                    traceId
            );
            ResponseEntity<JsonNode> repeated = postHold(
                    TEST_TOKEN,
                    "wallet-hold-request-2",
                    holdNo,
                    account.userId(),
                    "250.0",
                    traceId
            );
            ResponseEntity<JsonNode> queried = getHold(TEST_TOKEN, holdNo, traceId);

            assertSuccessHold(created, holdNo, account.userId(), "250.00", traceId);
            assertSuccessHold(repeated, holdNo, account.userId(), "250.00", traceId);
            assertSuccessHold(queried, holdNo, account.userId(), "250.00", traceId);
            assertThat(created.getBody().path("data").path("holdId").asText())
                    .isEqualTo(repeated.getBody().path("data").path("holdId").asText())
                    .isEqualTo(queried.getBody().path("data").path("holdId").asText());
            assertWallet(account.userId(), "750.00", "250.00", 1L);
            assertThat(findHolds(account.userId())).hasSize(1);
            assertThat(findLedgers(holdNo)).hasSize(1);
        } finally {
            deleteAccount(account.userId());
        }
    }

    @Test
    void authorizedApiReturnsStableBusinessAndValidationErrors() {
        TestAccount account = createAccount("100.00");
        String holdNo = uniqueHoldNo();
        try {
            ResponseEntity<JsonNode> insufficient = postHold(
                    TEST_TOKEN,
                    "wallet-error-request-1",
                    holdNo,
                    account.userId(),
                    "100.01",
                    null
            );
            assertFailure(insufficient, 409, "ACCOUNT_WALLET_INSUFFICIENT_BALANCE");

            ResponseEntity<JsonNode> malformedId = postHoldRaw(
                    TEST_TOKEN,
                    "wallet-error-request-2",
                    requestBody(holdNo, "9223372036854775808", "10.00"),
                    null
            );
            assertFailure(malformedId, 400, "COMMON_INVALID_ARGUMENT");

            ResponseEntity<JsonNode> missingRequestId = postHoldRaw(
                    TEST_TOKEN,
                    null,
                    requestBody(uniqueHoldNo(), Long.toString(account.userId()), "10.00"),
                    null
            );
            assertFailure(missingRequestId, 400, "COMMON_INVALID_ARGUMENT");

            ResponseEntity<JsonNode> missing = getHold(TEST_TOKEN, uniqueHoldNo(), null);
            assertFailure(missing, 404, "ACCOUNT_WALLET_HOLD_NOT_FOUND");

            assertWallet(account.userId(), "100.00", "0.00", 0L);
            assertThat(findHolds(account.userId())).isEmpty();
        } finally {
            deleteAccount(account.userId());
        }
    }

    private ResponseEntity<JsonNode> postHold(
            String token,
            String requestId,
            String holdNo,
            long userId,
            String amount,
            String traceId
    ) {
        return postHoldRaw(
                token,
                requestId,
                requestBody(holdNo, Long.toString(userId), amount),
                traceId
        );
    }

    private ResponseEntity<JsonNode> postHoldRaw(
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
        return client.postForEntity(
                "/internal/wallet-holds",
                new HttpEntity<>(body, headers),
                JsonNode.class
        );
    }

    private ResponseEntity<JsonNode> getHold(String token, String holdNo, String traceId) {
        return client.exchange(
                "/internal/wallet-holds/" + holdNo,
                HttpMethod.GET,
                new HttpEntity<>(headers(token, traceId)),
                JsonNode.class
        );
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

    private static Map<String, Object> requestBody(String holdNo, String userId, String amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("holdNo", holdNo);
        body.put("userId", userId);
        body.put("businessType", "AUCTION_DEPOSIT");
        body.put("amount", new BigDecimal(amount));
        return body;
    }

    private TestAccount createAccount(String initialBalance) {
        String username = "iw" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long userId = registrationStore.create(new AccountRegistrationStore.RegistrationData(
                username,
                "$2a$12$internal.wallet.api.placeholder.hash",
                "Internal wallet API",
                new BigDecimal(initialBalance),
                BigDecimal.ZERO
        ));
        return new TestAccount(userId, walletAccountMapper.selectByUserId(userId).getId());
    }

    private void assertWallet(long userId, String available, String frozen, long version) {
        WalletAccountEntity wallet = walletAccountMapper.selectByUserId(userId);
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo(available);
        assertThat(wallet.getFrozenBalance()).isEqualByComparingTo(frozen);
        assertThat(wallet.getVersion()).isEqualTo(version);
    }

    private List<WalletHoldEntity> findHolds(long userId) {
        return walletHoldMapper.selectList(new LambdaQueryWrapper<WalletHoldEntity>()
                .eq(WalletHoldEntity::getUserId, userId));
    }

    private List<WalletLedgerEntity> findLedgers(String businessNo) {
        return walletLedgerMapper.selectList(new LambdaQueryWrapper<WalletLedgerEntity>()
                .eq(WalletLedgerEntity::getBusinessNo, businessNo));
    }

    private void deleteAccount(long userId) {
        WalletAccountEntity wallet = walletAccountMapper.selectByUserId(userId);
        walletHoldMapper.delete(new LambdaQueryWrapper<WalletHoldEntity>()
                .eq(WalletHoldEntity::getUserId, userId));
        if (wallet != null) {
            walletLedgerMapper.delete(new LambdaQueryWrapper<WalletLedgerEntity>()
                    .eq(WalletLedgerEntity::getWalletId, wallet.getId()));
            walletAccountMapper.deleteById(wallet.getId());
        }
        for (UserRoleEntity role : userRoleMapper.selectByUserId(userId)) {
            userRoleMapper.delete(userId, role.getRoleCode());
        }
        userAccountMapper.deleteById(userId);
    }

    private static void assertSuccessHold(
            ResponseEntity<JsonNode> response,
            String holdNo,
            long userId,
            String amount,
            String traceId
    ) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(SecurityHeaders.TRACE_ID)).isEqualTo(traceId);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().path("traceId").asText()).isEqualTo(traceId);
        assertThat(response.getBody().path("data").path("holdId").isTextual()).isTrue();
        assertThat(response.getBody().path("data").path("holdNo").asText()).isEqualTo(holdNo);
        assertThat(response.getBody().path("data").path("userId").asText())
                .isEqualTo(Long.toString(userId));
        assertThat(response.getBody().path("data").path("businessType").asText())
                .isEqualTo("AUCTION_DEPOSIT");
        assertThat(response.getBody().path("data").path("amount").decimalValue())
                .isEqualByComparingTo(amount);
        assertThat(response.getBody().path("data").path("status").asText()).isEqualTo("HELD");
        assertThat(response.getBody().toString()).doesNotContain(TEST_TOKEN);
    }

    private static void assertFailure(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
        assertThat(response.getBody().path("data").isNull()).isTrue();
        assertThat(response.getBody().toString()).doesNotContain(TEST_TOKEN);
    }

    private static String uniqueHoldNo() {
        return "REGISTRATION:" + UUID.randomUUID().toString().replace("-", "");
    }

    private record TestAccount(long userId, long walletId) {
    }
}

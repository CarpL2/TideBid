package io.github.carpl2.tidebid.account;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.carpl2.tidebid.account.application.AccountRegistrationService;
import io.github.carpl2.tidebid.account.application.RegisterAccountCommand;
import io.github.carpl2.tidebid.account.application.RegisteredAccount;
import io.github.carpl2.tidebid.account.application.port.AccountRegistrationStore;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserRoleEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletLedgerEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserRoleMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletLedgerMapper;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local-db")
@Import(AccountJwtTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TIDEBID_ACCOUNT_DB_PASSWORD", matches = ".+")
class AccountRegistrationIntegrationTest {

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private AccountRegistrationService registrationService;

    @Autowired
    private AccountRegistrationStore registrationStore;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private WalletAccountMapper walletAccountMapper;

    @Autowired
    private WalletLedgerMapper walletLedgerMapper;

    @Test
    void registrationEndpointCreatesTheCompleteAggregateAndRejectsCaseVariantDuplicate() {
        String username = uniqueUsername();
        String rawPassword = "Secret-123";
        String traceId = "register-trace-1234";
        try {
            ResponseEntity<JsonNode> created = postRegistration(
                    username.toUpperCase(),
                    rawPassword,
                    "  注册测试用户  ",
                    "register-request-1234",
                    traceId
            );

            assertThat(created.getStatusCode().value()).isEqualTo(201);
            assertThat(created.getHeaders().getFirst(SecurityHeaders.TRACE_ID)).isEqualTo(traceId);
            assertThat(created.getBody()).isNotNull();
            assertThat(created.getBody().path("code").asText()).isEqualTo("SUCCESS");
            assertThat(created.getBody().path("traceId").asText()).isEqualTo(traceId);
            assertThat(created.getBody().path("data").path("username").asText()).isEqualTo(username);
            assertThat(created.getBody().path("data").path("nickname").asText()).isEqualTo("注册测试用户");
            assertThat(created.getBody().path("data").path("roles").get(0).asText()).isEqualTo("USER");
            assertThat(created.getBody().toString())
                    .doesNotContain(rawPassword)
                    .doesNotContain("passwordHash")
                    .doesNotContain("password_hash");

            UserAccountEntity user = findUser(username);
            assertThat(created.getBody().path("data").path("userId").isTextual()).isTrue();
            assertThat(created.getBody().path("data").path("userId").asText())
                    .isEqualTo(Long.toString(user.getId()));
            assertThat(user.getStatus()).isEqualTo("ACTIVE");
            assertThat(user.getNickname()).isEqualTo("注册测试用户");
            assertThat(user.getPasswordHash()).isNotEqualTo(rawPassword);
            assertThat(passwordEncoder.matches(rawPassword, user.getPasswordHash())).isTrue();

            assertThat(userRoleMapper.selectByUserId(user.getId()))
                    .extracting(UserRoleEntity::getRoleCode)
                    .containsExactly("USER");
            WalletAccountEntity wallet = findWallet(user.getId());
            assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("10000.00");
            assertThat(wallet.getFrozenBalance()).isEqualByComparingTo("0.00");
            WalletLedgerEntity ledger = findLedger(wallet.getId());
            assertThat(ledger.getBusinessNo()).isEqualTo("REGISTER_INIT:" + user.getId());
            assertThat(ledger.getLedgerType()).isEqualTo("INITIAL_CREDIT");
            assertThat(ledger.getAvailableDelta()).isEqualByComparingTo("10000.00");
            assertThat(ledger.getFrozenDelta()).isEqualByComparingTo("0.00");
            assertThat(ledger.getAvailableBalanceAfter()).isEqualByComparingTo("10000.00");
            assertThat(ledger.getFrozenBalanceAfter()).isEqualByComparingTo("0.00");

            ResponseEntity<JsonNode> duplicate = postRegistration(
                    username,
                    "Another-123",
                    "Duplicate",
                    "register-request-5678",
                    null
            );
            assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
            assertThat(duplicate.getBody()).isNotNull();
            assertThat(duplicate.getBody().path("code").asText())
                    .isEqualTo("ACCOUNT_USERNAME_ALREADY_EXISTS");
            assertThat(findUsers(username)).hasSize(1);
        } finally {
            deleteAccount(username);
        }
    }

    @Test
    void missingAndMalformedRequestIdsFailBeforeAnyWrite() {
        String username = uniqueUsername();
        try {
            ResponseEntity<JsonNode> missing = postRegistration(
                    username,
                    "Secret-123",
                    "Request ID test",
                    null,
                    null
            );
            assertThat(missing.getStatusCode().value()).isEqualTo(400);
            assertThat(missing.getBody()).isNotNull();
            assertThat(missing.getBody().path("code").asText()).isEqualTo("COMMON_INVALID_ARGUMENT");

            ResponseEntity<JsonNode> malformed = postRegistration(
                    username,
                    "Secret-123",
                    "Request ID test",
                    "bad id",
                    null
            );
            assertThat(malformed.getStatusCode().value()).isEqualTo(400);
            assertThat(malformed.getBody()).isNotNull();
            assertThat(malformed.getBody().path("code").asText()).isEqualTo("COMMON_INVALID_ARGUMENT");
            assertThat(findUsers(username)).isEmpty();
        } finally {
            deleteAccount(username);
        }
    }

    @Test
    void concurrentCaseVariantRegistrationProducesOneCompleteAccount() throws Exception {
        String username = uniqueUsername();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<RegistrationAttempt> first = executor.submit(() -> registerConcurrently(
                    ready,
                    start,
                    username.toUpperCase(),
                    "concurrent-request-1"
            ));
            Future<RegistrationAttempt> second = executor.submit(() -> registerConcurrently(
                    ready,
                    start,
                    username,
                    "concurrent-request-2"
            ));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<RegistrationAttempt> attempts = List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)
            );

            assertThat(attempts).filteredOn(RegistrationAttempt::succeeded).hasSize(1);
            assertThat(attempts)
                    .filteredOn(attempt -> !attempt.succeeded())
                    .extracting(RegistrationAttempt::errorCode)
                    .containsExactly("ACCOUNT_USERNAME_ALREADY_EXISTS");
            assertThat(findUsers(username)).hasSize(1);

            UserAccountEntity user = findUser(username);
            assertThat(userRoleMapper.selectByUserId(user.getId())).hasSize(1);
            WalletAccountEntity wallet = findWallet(user.getId());
            assertThat(findLedgers(wallet.getId())).hasSize(1);
        } finally {
            deleteAccount(username);
        }
    }

    @Test
    void persistenceFailureRollsBackUserRoleAndWalletAsOneTransaction() {
        String username = uniqueUsername();
        AccountRegistrationStore.RegistrationData invalidData = new AccountRegistrationStore.RegistrationData(
                username,
                "$2a$12$registration.rollback.placeholder.hash.value",
                "Rollback test",
                new BigDecimal("-1.00"),
                new BigDecimal("0.00")
        );
        try {
            assertThatThrownBy(() -> registrationStore.create(invalidData)).isInstanceOf(RuntimeException.class);
            assertThat(findUsers(username)).isEmpty();
        } finally {
            deleteAccount(username);
        }
    }

    private RegistrationAttempt registerConcurrently(
            CountDownLatch ready,
            CountDownLatch start,
            String username,
            String requestId
    ) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            RegisteredAccount account = registrationService.register(new RegisterAccountCommand(
                    requestId,
                    username,
                    "Secret-123",
                    "Concurrent test"
            ));
            return new RegistrationAttempt(account.userId(), null);
        } catch (BusinessException exception) {
            return new RegistrationAttempt(null, exception.errorCode().code());
        }
    }

    private ResponseEntity<JsonNode> postRegistration(
            String username,
            String password,
            String nickname,
            String requestId,
            String traceId
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (requestId != null) {
            headers.set(SecurityHeaders.REQUEST_ID, requestId);
        }
        if (traceId != null) {
            headers.set(SecurityHeaders.TRACE_ID, traceId);
        }
        Map<String, String> body = Map.of(
                "username", username,
                "password", password,
                "nickname", nickname
        );
        return client.postForEntity(
                "/api/auth/register",
                new HttpEntity<>(body, headers),
                JsonNode.class
        );
    }

    private UserAccountEntity findUser(String username) {
        return userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccountEntity>()
                .eq(UserAccountEntity::getUsername, username));
    }

    private List<UserAccountEntity> findUsers(String username) {
        return userAccountMapper.selectList(new LambdaQueryWrapper<UserAccountEntity>()
                .eq(UserAccountEntity::getUsername, username));
    }

    private WalletAccountEntity findWallet(long userId) {
        return walletAccountMapper.selectOne(new LambdaQueryWrapper<WalletAccountEntity>()
                .eq(WalletAccountEntity::getUserId, userId));
    }

    private WalletLedgerEntity findLedger(long walletId) {
        return walletLedgerMapper.selectOne(new LambdaQueryWrapper<WalletLedgerEntity>()
                .eq(WalletLedgerEntity::getWalletId, walletId));
    }

    private List<WalletLedgerEntity> findLedgers(long walletId) {
        return walletLedgerMapper.selectList(new LambdaQueryWrapper<WalletLedgerEntity>()
                .eq(WalletLedgerEntity::getWalletId, walletId));
    }

    private void deleteAccount(String username) {
        for (UserAccountEntity user : findUsers(username)) {
            List<WalletAccountEntity> wallets = walletAccountMapper.selectList(
                    new LambdaQueryWrapper<WalletAccountEntity>()
                            .eq(WalletAccountEntity::getUserId, user.getId())
            );
            for (WalletAccountEntity wallet : wallets) {
                walletLedgerMapper.delete(new LambdaQueryWrapper<WalletLedgerEntity>()
                        .eq(WalletLedgerEntity::getWalletId, wallet.getId()));
                walletAccountMapper.deleteById(wallet.getId());
            }
            for (UserRoleEntity role : userRoleMapper.selectByUserId(user.getId())) {
                userRoleMapper.delete(user.getId(), role.getRoleCode());
            }
            userAccountMapper.deleteById(user.getId());
        }
    }

    private static String uniqueUsername() {
        return "it" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record RegistrationAttempt(Long userId, String errorCode) {
        boolean succeeded() {
            return userId != null;
        }
    }
}

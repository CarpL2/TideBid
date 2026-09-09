package io.github.carpl2.tidebid.account;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.carpl2.tidebid.account.application.AccountRegistrationService;
import io.github.carpl2.tidebid.account.application.RegisterAccountCommand;
import io.github.carpl2.tidebid.account.application.RegisteredAccount;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserRoleEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletLedgerEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserRoleMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletLedgerMapper;
import io.github.carpl2.tidebid.security.JwtAccessTokenVerifier;
import io.github.carpl2.tidebid.security.JwtClaims;
import io.github.carpl2.tidebid.security.Role;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local-db")
@Import(AccountJwtTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TIDEBID_ACCOUNT_DB_PASSWORD", matches = ".+")
class AccountLoginIntegrationTest {

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private AccountRegistrationService registrationService;

    @Autowired
    private JwtAccessTokenVerifier tokenVerifier;

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private WalletAccountMapper walletAccountMapper;

    @Autowired
    private WalletLedgerMapper walletLedgerMapper;

    @Test
    void registeredUserCanLoginAndReceiveExpectedRs256Claims() {
        String username = uniqueUsername();
        String rawPassword = "Secret-123";
        String traceId = "login-trace-1234";
        try {
            RegisteredAccount registered = register(username, rawPassword);

            ResponseEntity<JsonNode> response = postLogin(
                    username.toUpperCase(),
                    rawPassword,
                    "login-request-1234",
                    traceId
            );

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getHeaders().getFirst(SecurityHeaders.TRACE_ID)).isEqualTo(traceId);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().path("code").asText()).isEqualTo("SUCCESS");
            assertThat(response.getBody().path("traceId").asText()).isEqualTo(traceId);
            assertThat(response.getBody().path("data").path("tokenType").asText()).isEqualTo("Bearer");
            assertThat(response.getBody().path("data").path("expiresIn").asLong()).isEqualTo(7200L);
            String accessToken = response.getBody().path("data").path("accessToken").asText();
            assertThat(accessToken).isNotBlank();
            assertThat(response.getBody().toString())
                    .doesNotContain(rawPassword)
                    .doesNotContain("passwordHash")
                    .doesNotContain("password_hash");

            JwtClaims claims = tokenVerifier.verify(accessToken);
            assertThat(claims.subject()).isEqualTo(username);
            assertThat(claims.userId()).isEqualTo(registered.userId());
            assertThat(claims.roles()).containsExactly(Role.USER);
            assertThat(claims.expiresAt().getEpochSecond() - claims.issuedAt().getEpochSecond()).isEqualTo(7200L);
            assertThat(claims.tokenId()).isNotBlank();
        } finally {
            deleteAccount(username);
        }
    }

    @Test
    void unknownUserAndWrongPasswordHaveIdenticalPublicResponse() {
        String username = uniqueUsername();
        try {
            register(username, "Secret-123");

            ResponseEntity<JsonNode> unknownUser = postLogin(
                    "missing_" + username.substring(2),
                    "Secret-123",
                    "login-request-2345",
                    null
            );
            ResponseEntity<JsonNode> wrongPassword = postLogin(
                    username,
                    "Wrong-123",
                    "login-request-3456",
                    null
            );

            assertThat(unknownUser.getStatusCode().value()).isEqualTo(401);
            assertThat(wrongPassword.getStatusCode().value()).isEqualTo(401);
            assertThat(unknownUser.getBody()).isNotNull();
            assertThat(wrongPassword.getBody()).isNotNull();
            assertThat(unknownUser.getBody().path("code").asText()).isEqualTo("ACCOUNT_INVALID_CREDENTIALS");
            assertThat(wrongPassword.getBody().path("code").asText())
                    .isEqualTo(unknownUser.getBody().path("code").asText());
            assertThat(wrongPassword.getBody().path("message").asText())
                    .isEqualTo(unknownUser.getBody().path("message").asText());
            assertThat(unknownUser.getBody().path("data").isNull()).isTrue();
            assertThat(wrongPassword.getBody().path("data").isNull()).isTrue();
        } finally {
            deleteAccount(username);
        }
    }

    @Test
    void disabledAccountWithCorrectPasswordReturnsForbidden() {
        String username = uniqueUsername();
        try {
            register(username, "Secret-123");
            UserAccountEntity user = findUser(username);
            user.setStatus("DISABLED");
            assertThat(userAccountMapper.updateById(user)).isEqualTo(1);

            ResponseEntity<JsonNode> response = postLogin(
                    username,
                    "Secret-123",
                    "login-request-4567",
                    null
            );

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().path("code").asText()).isEqualTo("ACCOUNT_DISABLED");
            assertThat(response.getBody().path("data").isNull()).isTrue();
            assertThat(response.getBody().has("accessToken")).isFalse();
        } finally {
            deleteAccount(username);
        }
    }

    @Test
    void loginRequiresWellFormedRequestId() {
        ResponseEntity<JsonNode> missing = postLogin(
                "missing_user",
                "Secret-123",
                null,
                null
        );
        ResponseEntity<JsonNode> malformed = postLogin(
                "missing_user",
                "Secret-123",
                "bad id",
                null
        );

        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
        assertThat(missing.getBody()).isNotNull();
        assertThat(malformed.getBody()).isNotNull();
        assertThat(missing.getBody().path("code").asText()).isEqualTo("COMMON_INVALID_ARGUMENT");
        assertThat(malformed.getBody().path("code").asText()).isEqualTo("COMMON_INVALID_ARGUMENT");
    }

    private RegisteredAccount register(String username, String password) {
        return registrationService.register(new RegisterAccountCommand(
                "login-setup-request",
                username,
                password,
                "Login integration test"
        ));
    }

    private ResponseEntity<JsonNode> postLogin(
            String username,
            String password,
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
        return client.postForEntity(
                "/api/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", password), headers),
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
}

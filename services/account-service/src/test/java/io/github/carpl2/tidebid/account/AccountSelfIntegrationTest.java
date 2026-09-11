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
import io.github.carpl2.tidebid.security.JwtAccessTokenIssuer;
import io.github.carpl2.tidebid.security.JwtTokenSettings;
import io.github.carpl2.tidebid.security.Role;
import io.github.carpl2.tidebid.security.RsaKeyPairMaterial;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local-db")
@Import(AccountJwtTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TIDEBID_ACCOUNT_DB_PASSWORD", matches = ".+")
class AccountSelfIntegrationTest {

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private AccountRegistrationService registrationService;

    @Autowired
    private JwtAccessTokenIssuer tokenIssuer;

    @Autowired
    private RsaKeyPairMaterial keyPair;

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private WalletAccountMapper walletAccountMapper;

    @Autowired
    private WalletLedgerMapper walletLedgerMapper;

    @Test
    void validTokenReturnsItsOwnProfileAndWalletDespiteForgedIdentityHeaders() {
        String username = uniqueUsername();
        String otherUsername = uniqueUsername();
        try {
            RegisteredAccount account = register(username, "Current Alice");
            RegisteredAccount otherAccount = register(otherUsername, "Other Bob");
            WalletAccountEntity otherWallet = walletAccountMapper.selectByUserId(otherAccount.userId());
            otherWallet.setAvailableBalance(new BigDecimal("4321.00"));
            assertThat(walletAccountMapper.updateById(otherWallet)).isEqualTo(1);
            String token = login(username, "Secret-123");

            HttpHeaders headers = authenticatedHeaders(token, "self-trace-1234");
            headers.set(SecurityHeaders.INTERNAL_USER_ID, Long.toString(otherAccount.userId()));
            headers.set(SecurityHeaders.INTERNAL_USER_ROLES, Role.ADMIN.name());

            ResponseEntity<JsonNode> userResponse = get("/api/users/me", headers);
            ResponseEntity<JsonNode> walletResponse = get("/api/wallets/me", headers);

            assertSuccessEnvelope(userResponse, "self-trace-1234");
            assertThat(userResponse.getBody().path("data").path("userId").isTextual()).isTrue();
            assertThat(userResponse.getBody().path("data").path("userId").asText())
                    .isEqualTo(Long.toString(account.userId()));
            assertThat(userResponse.getBody().path("data").path("username").asText()).isEqualTo(username);
            assertThat(userResponse.getBody().path("data").path("nickname").asText()).isEqualTo("Current Alice");
            JsonNode roles = userResponse.getBody().path("data").path("roles");
            assertThat(roles.isArray()).isTrue();
            assertThat(roles.size()).isEqualTo(1);
            assertThat(roles.get(0).asText()).isEqualTo("USER");
            assertThat(userResponse.getBody().toString()).doesNotContain("password");

            assertSuccessEnvelope(walletResponse, "self-trace-1234");
            assertThat(walletResponse.getBody().path("data").path("userId").isTextual()).isTrue();
            assertThat(walletResponse.getBody().path("data").path("userId").asText())
                    .isEqualTo(Long.toString(account.userId()));
            assertThat(walletResponse.getBody().path("data").path("availableBalance").decimalValue())
                    .isEqualByComparingTo("10000.00");
            assertThat(walletResponse.getBody().path("data").path("frozenBalance").decimalValue())
                    .isEqualByComparingTo("0.00");
        } finally {
            deleteAccount(username);
            deleteAccount(otherUsername);
        }
    }

    @Test
    void missingMalformedTamperedAndExpiredTokensReturnUniformJsonUnauthorized() {
        String username = uniqueUsername();
        try {
            RegisteredAccount account = register(username, "Token Alice");
            String validToken = login(username, "Secret-123");
            String tamperedToken = tamperSignature(validToken);
            Clock expiredClock = Clock.fixed(Instant.now().minusSeconds(3 * 60 * 60), ZoneOffset.UTC);
            String expiredToken = new JwtAccessTokenIssuer(
                    keyPair,
                    JwtTokenSettings.tideBidDefaults(),
                    expiredClock
            ).issue(username, account.userId(), Set.of(Role.USER)).value();

            List<ResponseEntity<JsonNode>> responses = List.of(
                    get("/api/users/me", new HttpHeaders()),
                    get("/api/users/me", authorizationHeaders("Basic not-a-bearer-token")),
                    get("/api/users/me", authenticatedHeaders(tamperedToken, null)),
                    get("/api/wallets/me", authenticatedHeaders(expiredToken, null))
            );

            for (ResponseEntity<JsonNode> response : responses) {
                assertThat(response.getStatusCode().value()).isEqualTo(401);
                assertThat(response.getHeaders().getContentType()).isNotNull();
                assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON))
                        .isTrue();
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().path("code").asText()).isEqualTo("COMMON_UNAUTHENTICATED");
                assertThat(response.getBody().path("data").isNull()).isTrue();
                assertThat(response.getBody().path("traceId").asText()).matches("[a-f0-9]{32}");
                assertThat(response.getBody().toString()).doesNotContain(validToken);
            }
        } finally {
            deleteAccount(username);
        }
    }

    @Test
    void tokenSubjectMustMatchPersistedUsername() {
        String username = uniqueUsername();
        try {
            RegisteredAccount account = register(username, "Subject Alice");
            String mismatchedToken = tokenIssuer.issue(
                    "another_subject",
                    account.userId(),
                    Set.of(Role.USER)
            ).value();

            ResponseEntity<JsonNode> response = get(
                    "/api/users/me",
                    authenticatedHeaders(mismatchedToken, null)
            );

            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().path("code").asText()).isEqualTo("COMMON_UNAUTHENTICATED");
        } finally {
            deleteAccount(username);
        }
    }

    @Test
    void disablingAccountInvalidatesExistingTokenForSelfQueries() {
        String username = uniqueUsername();
        try {
            register(username, "Disabled Alice");
            String token = login(username, "Secret-123");
            UserAccountEntity user = findUser(username);
            user.setStatus("DISABLED");
            assertThat(userAccountMapper.updateById(user)).isEqualTo(1);

            ResponseEntity<JsonNode> response = get(
                    "/api/wallets/me",
                    authenticatedHeaders(token, null)
            );

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().path("code").asText()).isEqualTo("ACCOUNT_DISABLED");
            assertThat(response.getBody().path("data").isNull()).isTrue();
        } finally {
            deleteAccount(username);
        }
    }

    private RegisteredAccount register(String username, String nickname) {
        return registrationService.register(new RegisterAccountCommand(
                "self-setup-request",
                username,
                "Secret-123",
                nickname
        ));
    }

    private String login(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(SecurityHeaders.REQUEST_ID, "self-login-request");
        ResponseEntity<JsonNode> response = client.postForEntity(
                "/api/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", password), headers),
                JsonNode.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().path("data").path("accessToken").asText();
    }

    private ResponseEntity<JsonNode> get(String path, HttpHeaders headers) {
        return client.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private static HttpHeaders authenticatedHeaders(String token, String traceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (traceId != null) {
            headers.set(SecurityHeaders.TRACE_ID, traceId);
        }
        return headers;
    }

    private static HttpHeaders authorizationHeaders(String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(SecurityHeaders.AUTHORIZATION, authorization);
        return headers;
    }

    private static void assertSuccessEnvelope(ResponseEntity<JsonNode> response, String traceId) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(SecurityHeaders.TRACE_ID)).isEqualTo(traceId);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().path("traceId").asText()).isEqualTo(traceId);
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
            WalletAccountEntity wallet = walletAccountMapper.selectByUserId(user.getId());
            if (wallet != null) {
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

    private static String tamperSignature(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        char original = token.charAt(signatureStart);
        char replacement = original == 'A' ? 'B' : 'A';
        return token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
    }

    private static String uniqueUsername() {
        return "it" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

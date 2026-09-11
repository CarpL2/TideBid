package io.github.carpl2.tidebid.account;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.carpl2.tidebid.account.application.AccountRegistrationService;
import io.github.carpl2.tidebid.account.application.DevelopmentAdminBootstrapService;
import io.github.carpl2.tidebid.account.application.DevelopmentAdminCommand;
import io.github.carpl2.tidebid.account.application.RegisterAccountCommand;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserRoleEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletLedgerEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserRoleMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletLedgerMapper;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local-db")
@Import(AccountJwtTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TIDEBID_ACCOUNT_DB_PASSWORD", matches = ".+")
class DevelopmentAdminIntegrationTest {

    private static final String ADMIN_USERNAME = uniqueUsername("adm");
    private static final String ADMIN_PASSWORD = "Admin-Secret-123";
    private static final String ADMIN_NICKNAME = "Integration Administrator";

    @DynamicPropertySource
    static void developmentAdminProperties(DynamicPropertyRegistry registry) {
        registry.add("tidebid.development-admin.enabled", () -> true);
        registry.add("tidebid.development-admin.username", () -> ADMIN_USERNAME.toUpperCase());
        registry.add("tidebid.development-admin.password", () -> ADMIN_PASSWORD);
        registry.add("tidebid.development-admin.nickname", () -> "  " + ADMIN_NICKNAME + "  ");
    }

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private DevelopmentAdminBootstrapService bootstrapService;

    @Autowired
    private AccountRegistrationService registrationService;

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
    void bootstrapIsIdempotentAndAdminAccessRechecksCurrentDatabaseRole() {
        String ordinaryUsername = uniqueUsername("usr");
        try {
            UserAccountEntity admin = findUser(ADMIN_USERNAME);
            assertThat(admin).isNotNull();
            assertThat(admin.getNickname()).isEqualTo(ADMIN_NICKNAME);
            assertThat(passwordEncoder.matches(ADMIN_PASSWORD, admin.getPasswordHash())).isTrue();
            assertThat(roleNames(admin.getId())).containsExactly("ADMIN", "USER");
            WalletAccountEntity wallet = walletAccountMapper.selectByUserId(admin.getId());
            assertThat(wallet).isNotNull();
            assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("10000.00");
            assertThat(wallet.getFrozenBalance()).isEqualByComparingTo("0.00");
            assertThat(findLedgers(wallet.getId())).hasSize(1);

            bootstrapService.ensureAdmin(new DevelopmentAdminCommand(
                    ADMIN_USERNAME,
                    ADMIN_PASSWORD,
                    ADMIN_NICKNAME
            ));
            assertThat(findUsers(ADMIN_USERNAME)).hasSize(1);
            assertThat(roleNames(admin.getId())).containsExactly("ADMIN", "USER");
            assertThat(walletAccountMapper.selectList(new LambdaQueryWrapper<WalletAccountEntity>()
                    .eq(WalletAccountEntity::getUserId, admin.getId()))).hasSize(1);
            assertThat(findLedgers(wallet.getId())).hasSize(1);

            String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD, "admin-login-request");
            ResponseEntity<JsonNode> allowed = getAdminAccess(adminToken, "admin-access-trace");
            assertThat(allowed.getStatusCode().value()).isEqualTo(200);
            assertThat(allowed.getHeaders().getFirst(SecurityHeaders.TRACE_ID)).isEqualTo("admin-access-trace");
            assertThat(allowed.getBody()).isNotNull();
            assertThat(allowed.getBody().path("data").path("userId").isTextual()).isTrue();
            assertThat(allowed.getBody().path("data").path("userId").asText())
                    .isEqualTo(Long.toString(admin.getId()));
            assertThat(allowed.getBody().path("data").path("username").asText()).isEqualTo(ADMIN_USERNAME);
            JsonNode roles = allowed.getBody().path("data").path("roles");
            assertThat(roles.isArray()).isTrue();
            assertThat(roles.size()).isEqualTo(2);
            assertThat(roles.get(0).asText()).isEqualTo("ADMIN");
            assertThat(roles.get(1).asText()).isEqualTo("USER");
            assertThat(allowed.getBody().toString()).doesNotContain(ADMIN_PASSWORD).doesNotContain("password");

            registrationService.register(new RegisterAccountCommand(
                    "ordinary-register-request",
                    ordinaryUsername,
                    "Ordinary-Secret-123",
                    "Ordinary User"
            ));
            String ordinaryToken = login(ordinaryUsername, "Ordinary-Secret-123", "ordinary-login-request");
            ResponseEntity<JsonNode> ordinaryDenied = getAdminAccess(ordinaryToken, null);
            assertForbidden(ordinaryDenied);

            assertThat(userRoleMapper.delete(admin.getId(), Role.ADMIN.name())).isEqualTo(1);
            ResponseEntity<JsonNode> revokedDenied = getAdminAccess(adminToken, null);
            assertForbidden(revokedDenied);
        } finally {
            deleteAccount(ordinaryUsername);
            deleteAccount(ADMIN_USERNAME);
        }
    }

    private String login(String username, String password, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(SecurityHeaders.REQUEST_ID, requestId);
        ResponseEntity<JsonNode> response = client.postForEntity(
                "/api/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", password), headers),
                JsonNode.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().path("data").path("accessToken").asText();
    }

    private ResponseEntity<JsonNode> getAdminAccess(String token, String traceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (traceId != null) {
            headers.set(SecurityHeaders.TRACE_ID, traceId);
        }
        return client.exchange(
                "/api/admin/access-check",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class
        );
    }

    private static void assertForbidden(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("COMMON_FORBIDDEN");
        assertThat(response.getBody().path("data").isNull()).isTrue();
    }

    private UserAccountEntity findUser(String username) {
        return userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccountEntity>()
                .eq(UserAccountEntity::getUsername, username));
    }

    private List<UserAccountEntity> findUsers(String username) {
        return userAccountMapper.selectList(new LambdaQueryWrapper<UserAccountEntity>()
                .eq(UserAccountEntity::getUsername, username));
    }

    private List<String> roleNames(long userId) {
        return userRoleMapper.selectByUserId(userId).stream()
                .map(UserRoleEntity::getRoleCode)
                .toList();
    }

    private List<WalletLedgerEntity> findLedgers(long walletId) {
        return walletLedgerMapper.selectList(new LambdaQueryWrapper<WalletLedgerEntity>()
                .eq(WalletLedgerEntity::getWalletId, walletId));
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

    private static String uniqueUsername(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

package io.github.carpl2.tidebid.account.application;

import io.github.carpl2.tidebid.account.api.AccessTokenResponse;
import io.github.carpl2.tidebid.account.api.LoginRequest;
import io.github.carpl2.tidebid.account.application.port.AccountAuthenticationStore;
import io.github.carpl2.tidebid.account.application.port.AccountAuthenticationStore.StoredAccount;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.security.IssuedAccessToken;
import io.github.carpl2.tidebid.security.JwtAccessTokenIssuer;
import io.github.carpl2.tidebid.security.JwtAccessTokenVerifier;
import io.github.carpl2.tidebid.security.JwtClaims;
import io.github.carpl2.tidebid.security.JwtTokenSettings;
import io.github.carpl2.tidebid.security.Role;
import io.github.carpl2.tidebid.security.RsaKeyPairMaterial;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountAuthenticationServiceTest {

    private static final String REQUEST_ID = "login-request-1234";
    private static final String RAW_PASSWORD = "Secret-123";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void authenticatesCanonicalUserAndIssuesVerifiableTokenWithoutLeakingSecrets() throws Exception {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        String passwordHash = encoder.encode(RAW_PASSWORD);
        AtomicReference<String> queriedUsername = new AtomicReference<>();
        AccountAuthenticationStore store = username -> {
            queriedUsername.set(username);
            return Optional.of(account(passwordHash, "ACTIVE"));
        };
        RsaKeyPairMaterial keyPair = keyPair();
        AccountAuthenticationService service = new AccountAuthenticationService(
                encoder,
                store,
                new JwtAccessTokenIssuer(keyPair, JwtTokenSettings.tideBidDefaults(), CLOCK)
        );
        AuthenticateAccountCommand command = new AuthenticateAccountCommand(
                REQUEST_ID,
                "Alice_01",
                RAW_PASSWORD
        );

        IssuedAccessToken issuedToken = service.authenticate(command);
        JwtClaims claims = new JwtAccessTokenVerifier(
                keyPair.publicKey(),
                JwtTokenSettings.tideBidDefaults(),
                CLOCK
        ).verify(issuedToken.value());

        assertThat(queriedUsername.get()).isEqualTo("alice_01");
        assertThat(claims.subject()).isEqualTo("alice_01");
        assertThat(claims.userId()).isEqualTo(101L);
        assertThat(claims.roles()).containsExactly(Role.USER);
        assertThat(issuedToken.expiresInSeconds()).isEqualTo(7200L);
        assertThat(command.toString()).contains("[REDACTED]").doesNotContain(RAW_PASSWORD);
        assertThat(new LoginRequest("Alice_01", RAW_PASSWORD).toString())
                .contains("[REDACTED]")
                .doesNotContain(RAW_PASSWORD);
        assertThat(new AccessTokenResponse(issuedToken.value(), "Bearer", issuedToken.expiresInSeconds()).toString())
                .contains("[REDACTED]")
                .doesNotContain(issuedToken.value());
        assertThat(account(passwordHash, "ACTIVE").toString())
                .contains("[REDACTED]")
                .doesNotContain(passwordHash);
    }

    @Test
    void missingUserAndWrongPasswordReturnIdenticalPublicFailure() throws Exception {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        String passwordHash = encoder.encode(RAW_PASSWORD);
        AccountAuthenticationService missingUserService = service(encoder, username -> Optional.empty());
        AccountAuthenticationService wrongPasswordService = service(
                encoder,
                username -> Optional.of(account(passwordHash, "ACTIVE"))
        );

        BusinessException missingUser = captureFailure(missingUserService, "missing_user", RAW_PASSWORD);
        BusinessException wrongPassword = captureFailure(wrongPasswordService, "Alice_01", "Wrong-123");

        assertThat(missingUser.errorCode().code()).isEqualTo("ACCOUNT_INVALID_CREDENTIALS");
        assertThat(missingUser.errorCode().httpStatus()).isEqualTo(401);
        assertThat(wrongPassword.errorCode().code()).isEqualTo(missingUser.errorCode().code());
        assertThat(wrongPassword.getMessage()).isEqualTo(missingUser.getMessage());
    }

    @Test
    void invalidUsernameShapeAlsoUsesCredentialFailureAfterDummyHashCheck() throws Exception {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        AtomicReference<Boolean> queried = new AtomicReference<>(false);
        AccountAuthenticationService service = service(encoder, username -> {
            queried.set(true);
            return Optional.empty();
        });

        BusinessException failure = captureFailure(service, " invalid ", RAW_PASSWORD);

        assertThat(failure.errorCode().code()).isEqualTo("ACCOUNT_INVALID_CREDENTIALS");
        assertThat(queried.get()).isFalse();
    }

    @Test
    void correctPasswordForDisabledAccountReturnsForbiddenWithoutToken() throws Exception {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        String passwordHash = encoder.encode(RAW_PASSWORD);
        AccountAuthenticationService service = service(
                encoder,
                username -> Optional.of(account(passwordHash, "DISABLED"))
        );

        assertThatThrownBy(() -> service.authenticate(command("alice_01", RAW_PASSWORD)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode().code()).isEqualTo("ACCOUNT_DISABLED");
                    assertThat(exception.errorCode().httpStatus()).isEqualTo(403);
                });
    }

    @Test
    void invalidRequestIdFailsBeforeAccountLookup() throws Exception {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        AccountAuthenticationService service = service(encoder, username -> {
            throw new AssertionError("Account lookup must not run for an invalid request ID");
        });

        assertThatThrownBy(() -> service.authenticate(new AuthenticateAccountCommand(
                "bad id",
                "alice_01",
                RAW_PASSWORD
        )))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("COMMON_INVALID_ARGUMENT"));
    }

    private static BusinessException captureFailure(
            AccountAuthenticationService service,
            String username,
            String password
    ) {
        try {
            service.authenticate(command(username, password));
            throw new AssertionError("Expected authentication to fail");
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private static AccountAuthenticationService service(
            BCryptPasswordEncoder encoder,
            AccountAuthenticationStore store
    ) throws Exception {
        return new AccountAuthenticationService(
                encoder,
                store,
                new JwtAccessTokenIssuer(keyPair(), JwtTokenSettings.tideBidDefaults(), CLOCK)
        );
    }

    private static StoredAccount account(String passwordHash, String status) {
        return new StoredAccount(101L, "alice_01", passwordHash, status, Set.of(Role.USER));
    }

    private static AuthenticateAccountCommand command(String username, String password) {
        return new AuthenticateAccountCommand(REQUEST_ID, username, password);
    }

    private static RsaKeyPairMaterial keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        return new RsaKeyPairMaterial(
                (RSAPublicKey) keyPair.getPublic(),
                (RSAPrivateKey) keyPair.getPrivate()
        );
    }
}

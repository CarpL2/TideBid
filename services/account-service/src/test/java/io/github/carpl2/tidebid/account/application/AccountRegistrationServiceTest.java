package io.github.carpl2.tidebid.account.application;

import io.github.carpl2.tidebid.account.api.RegisterRequest;
import io.github.carpl2.tidebid.account.application.port.AccountRegistrationStore;
import io.github.carpl2.tidebid.account.application.port.DuplicateUsernameException;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountRegistrationServiceTest {

    private static final String REQUEST_ID = "request-12345678";

    @Test
    void normalizesPublicFieldsHashesExactPasswordAndRedactsDiagnosticStrings() {
        AtomicReference<AccountRegistrationStore.RegistrationData> saved = new AtomicReference<>();
        AccountRegistrationStore store = new StubStore(false, data -> {
            saved.set(data);
            return 101L;
        });
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        AccountRegistrationService service = new AccountRegistrationService(encoder, store);
        String rawPassword = " Secret9";
        RegisterAccountCommand command = new RegisterAccountCommand(
                REQUEST_ID,
                "Alice_01",
                rawPassword,
                "  Alice 昵称  "
        );

        RegisteredAccount result = service.register(command);

        assertThat(result.userId()).isEqualTo(101L);
        assertThat(result.username()).isEqualTo("alice_01");
        assertThat(result.nickname()).isEqualTo("Alice 昵称");
        assertThat(result.roles()).containsExactly("USER");
        assertThat(saved.get().canonicalUsername()).isEqualTo("alice_01");
        assertThat(saved.get().nickname()).isEqualTo("Alice 昵称");
        assertThat(saved.get().initialAvailableBalance()).isEqualByComparingTo("10000.00");
        assertThat(saved.get().initialFrozenBalance()).isEqualByComparingTo("0.00");
        assertThat(saved.get().passwordHash()).isNotEqualTo(rawPassword);
        assertThat(encoder.matches(rawPassword, saved.get().passwordHash())).isTrue();
        assertThat(command.toString()).contains("[REDACTED]").doesNotContain(rawPassword);
        assertThat(saved.get().toString()).contains("[REDACTED]").doesNotContain(saved.get().passwordHash());
        assertThat(new RegisterRequest("Alice_01", rawPassword, "Alice").toString())
                .contains("[REDACTED]")
                .doesNotContain(rawPassword);
    }

    @Test
    void rejectsUsernameWhitespaceInsteadOfSilentlyChangingLoginIdentity() {
        AccountRegistrationService service = serviceWithUnusedStore();

        assertThatThrownBy(() -> service.register(new RegisterAccountCommand(
                REQUEST_ID,
                " Alice_01",
                "Secret123",
                "Alice"
        )))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void rejectsPasswordsBeyondBcryptUtf8ByteLimit() {
        AccountRegistrationService service = serviceWithUnusedStore();
        String password = "密".repeat(25);

        assertThat(password).hasSize(25);
        assertThatThrownBy(() -> service.register(new RegisterAccountCommand(
                REQUEST_ID,
                "alice_01",
                password,
                "Alice"
        )))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode().code()).isEqualTo("COMMON_INVALID_ARGUMENT");
                    assertThat(exception.getMessage()).contains("72 UTF-8 bytes").doesNotContain(password);
                });
    }

    @Test
    void rejectsInvalidRequestIdBeforeAccessingPersistence() {
        AccountRegistrationService service = serviceWithUnusedStore();

        assertThatThrownBy(() -> service.register(new RegisterAccountCommand(
                "bad id",
                "alice_01",
                "Secret123",
                "Alice"
        )))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void mapsExistingAndRacingDuplicateUsernamesToSameStableConflict() {
        PasswordEncoder mustNotEncode = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                throw new AssertionError("Password hashing must not run after a duplicate pre-check");
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return false;
            }
        };
        AccountRegistrationService preCheckService = new AccountRegistrationService(
                mustNotEncode,
                new StubStore(true, data -> 1L)
        );

        assertUsernameConflict(preCheckService);

        AccountRegistrationService raceService = new AccountRegistrationService(
                new BCryptPasswordEncoder(4),
                new StubStore(false, data -> {
                    throw new DuplicateUsernameException(new DuplicateKeyException("duplicate"));
                })
        );
        assertUsernameConflict(raceService);
    }

    private static void assertUsernameConflict(AccountRegistrationService service) {
        assertThatThrownBy(() -> service.register(new RegisterAccountCommand(
                REQUEST_ID,
                "Alice_01",
                "Secret123",
                "Alice"
        )))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode().code()).isEqualTo("ACCOUNT_USERNAME_ALREADY_EXISTS");
                    assertThat(exception.errorCode().httpStatus()).isEqualTo(409);
                });
    }

    private static AccountRegistrationService serviceWithUnusedStore() {
        return new AccountRegistrationService(new BCryptPasswordEncoder(4), new AccountRegistrationStore() {
            @Override
            public boolean usernameExists(String canonicalUsername) {
                throw new AssertionError("Persistence must not be reached for invalid input");
            }

            @Override
            public long create(RegistrationData registrationData) {
                throw new AssertionError("Persistence must not be reached for invalid input");
            }
        });
    }

    private record StubStore(boolean exists, RegistrationAction action) implements AccountRegistrationStore {
        @Override
        public boolean usernameExists(String canonicalUsername) {
            return exists;
        }

        @Override
        public long create(RegistrationData registrationData) {
            return action.create(registrationData);
        }
    }

    @FunctionalInterface
    private interface RegistrationAction {
        long create(AccountRegistrationStore.RegistrationData registrationData);
    }
}

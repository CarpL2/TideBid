package io.github.carpl2.tidebid.account.application;

import io.github.carpl2.tidebid.account.application.port.AccountAuthenticationStore;
import io.github.carpl2.tidebid.account.application.port.AccountAuthenticationStore.StoredAccount;
import io.github.carpl2.tidebid.account.application.port.AccountRegistrationStore;
import io.github.carpl2.tidebid.account.application.port.AccountRoleStore;
import io.github.carpl2.tidebid.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevelopmentAdminBootstrapServiceTest {

    private static final String PASSWORD = "Admin-Secret-123";

    @Test
    void createsNormalizedAdministratorAggregateAndAddsAdminRole() {
        PasswordEncoder encoder = new BCryptPasswordEncoder(4);
        AtomicReference<AccountRegistrationStore.RegistrationData> registration = new AtomicReference<>();
        AtomicReference<RoleAssignment> roleAssignment = new AtomicReference<>();
        DevelopmentAdminBootstrapService service = service(
                encoder,
                Optional.empty(),
                data -> {
                    registration.set(data);
                    return 101L;
                },
                (userId, role) -> roleAssignment.set(new RoleAssignment(userId, role))
        );
        DevelopmentAdminCommand command = new DevelopmentAdminCommand(
                "TideBid_Admin",
                PASSWORD,
                "  TideBid 管理员  "
        );

        service.ensureAdmin(command);

        assertThat(registration.get().canonicalUsername()).isEqualTo("tidebid_admin");
        assertThat(registration.get().nickname()).isEqualTo("TideBid 管理员");
        assertThat(registration.get().initialAvailableBalance()).isEqualByComparingTo("10000.00");
        assertThat(registration.get().initialFrozenBalance()).isEqualByComparingTo("0.00");
        assertThat(encoder.matches(PASSWORD, registration.get().passwordHash())).isTrue();
        assertThat(roleAssignment.get()).isEqualTo(new RoleAssignment(101L, Role.ADMIN));
        assertThat(command.toString()).contains("[REDACTED]").doesNotContain(PASSWORD);
    }

    @Test
    void acceptsMatchingExistingAdministratorWithoutWriting() {
        PasswordEncoder encoder = new BCryptPasswordEncoder(4);
        StoredAccount existing = account(encoder, "ACTIVE", Set.of(Role.USER, Role.ADMIN), PASSWORD);
        DevelopmentAdminBootstrapService service = service(
                encoder,
                Optional.of(existing),
                data -> {
                    throw new AssertionError("Existing administrator must not be recreated");
                },
                (userId, role) -> {
                    throw new AssertionError("Existing administrator must not receive a duplicate role");
                }
        );

        service.ensureAdmin(new DevelopmentAdminCommand("TIDEBID_ADMIN", PASSWORD, "Administrator"));
    }

    @Test
    void rejectsOrdinaryDisabledOrPasswordMismatchedExistingAccounts() {
        PasswordEncoder encoder = new BCryptPasswordEncoder(4);

        assertExistingRejected(encoder, account(encoder, "ACTIVE", Set.of(Role.USER), PASSWORD), PASSWORD);
        assertExistingRejected(
                encoder,
                account(encoder, "DISABLED", Set.of(Role.USER, Role.ADMIN), PASSWORD),
                PASSWORD
        );
        assertExistingRejected(
                encoder,
                account(encoder, "ACTIVE", Set.of(Role.USER, Role.ADMIN), PASSWORD),
                "Wrong-Admin-Password"
        );
    }

    @Test
    void rejectsInvalidConfigurationWithoutLeakingPassword() {
        PasswordEncoder encoder = new BCryptPasswordEncoder(4);
        DevelopmentAdminBootstrapService service = service(
                encoder,
                Optional.empty(),
                data -> 1L,
                (userId, role) -> {
                }
        );
        String oversizedPassword = "密".repeat(25);

        assertThatThrownBy(() -> service.ensureAdmin(new DevelopmentAdminCommand(
                "bad username",
                oversizedPassword,
                "Administrator"
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("username")
                .hasMessageNotContaining(oversizedPassword);

        assertThatThrownBy(() -> service.ensureAdmin(new DevelopmentAdminCommand(
                "valid_admin",
                oversizedPassword,
                "Administrator"
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("72 UTF-8 bytes")
                .hasMessageNotContaining(oversizedPassword);
    }

    private static void assertExistingRejected(
            PasswordEncoder encoder,
            StoredAccount existing,
            String configuredPassword
    ) {
        DevelopmentAdminBootstrapService service = service(
                encoder,
                Optional.of(existing),
                data -> {
                    throw new AssertionError("Invalid existing account must not be recreated");
                },
                (userId, role) -> {
                    throw new AssertionError("Invalid existing account must not be promoted");
                }
        );

        assertThatThrownBy(() -> service.ensureAdmin(new DevelopmentAdminCommand(
                "tidebid_admin",
                configuredPassword,
                "Administrator"
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(configuredPassword);
    }

    private static StoredAccount account(
            PasswordEncoder encoder,
            String status,
            Set<Role> roles,
            String password
    ) {
        return new StoredAccount(
                101L,
                "tidebid_admin",
                encoder.encode(password),
                status,
                roles
        );
    }

    private static DevelopmentAdminBootstrapService service(
            PasswordEncoder encoder,
            Optional<StoredAccount> existing,
            RegistrationAction registrationAction,
            AccountRoleStore roleStore
    ) {
        AccountAuthenticationStore authenticationStore = username -> existing;
        AccountRegistrationStore registrationStore = new AccountRegistrationStore() {
            @Override
            public boolean usernameExists(String canonicalUsername) {
                return existing.isPresent();
            }

            @Override
            public long create(RegistrationData registrationData) {
                return registrationAction.create(registrationData);
            }
        };
        return new DevelopmentAdminBootstrapService(
                encoder,
                authenticationStore,
                registrationStore,
                roleStore
        );
    }

    private record RoleAssignment(long userId, Role role) {
    }

    @FunctionalInterface
    private interface RegistrationAction {
        long create(AccountRegistrationStore.RegistrationData registrationData);
    }
}

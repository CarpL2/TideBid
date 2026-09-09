package io.github.carpl2.tidebid.account.application;

import io.github.carpl2.tidebid.account.application.port.AccountAuthenticationStore;
import io.github.carpl2.tidebid.account.application.port.AccountAuthenticationStore.StoredAccount;
import io.github.carpl2.tidebid.account.application.port.AccountRegistrationStore;
import io.github.carpl2.tidebid.account.application.port.AccountRegistrationStore.RegistrationData;
import io.github.carpl2.tidebid.account.application.port.AccountRoleStore;
import io.github.carpl2.tidebid.account.application.port.DuplicateUsernameException;
import io.github.carpl2.tidebid.security.Role;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

@Service
@Profile({"local-db", "nacos"})
public class DevelopmentAdminBootstrapService {

    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final BigDecimal INITIAL_AVAILABLE_BALANCE = new BigDecimal("10000.00");
    private static final BigDecimal INITIAL_FROZEN_BALANCE = new BigDecimal("0.00");
    private static final Set<Role> REQUIRED_ROLES = Set.of(Role.USER, Role.ADMIN);

    private final PasswordEncoder passwordEncoder;
    private final AccountAuthenticationStore authenticationStore;
    private final AccountRegistrationStore registrationStore;
    private final AccountRoleStore roleStore;

    public DevelopmentAdminBootstrapService(
            PasswordEncoder passwordEncoder,
            AccountAuthenticationStore authenticationStore,
            AccountRegistrationStore registrationStore,
            AccountRoleStore roleStore
    ) {
        this.passwordEncoder = passwordEncoder;
        this.authenticationStore = authenticationStore;
        this.registrationStore = registrationStore;
        this.roleStore = roleStore;
    }

    @Transactional
    public void ensureAdmin(DevelopmentAdminCommand command) {
        ValidatedDevelopmentAdmin admin = validate(command);
        Optional<StoredAccount> existing = authenticationStore.findByCanonicalUsername(admin.username());
        if (existing.isPresent()) {
            verifyExistingAdmin(existing.orElseThrow(), admin.password());
            return;
        }

        RegistrationData registration = new RegistrationData(
                admin.username(),
                passwordEncoder.encode(admin.password()),
                admin.nickname(),
                INITIAL_AVAILABLE_BALANCE,
                INITIAL_FROZEN_BALANCE
        );
        try {
            long userId = registrationStore.create(registration);
            roleStore.addRole(userId, Role.ADMIN);
        } catch (DuplicateUsernameException exception) {
            throw invalidConfiguration("Development administrator username became occupied", exception);
        }
    }

    private void verifyExistingAdmin(StoredAccount account, String configuredPassword) {
        if (!ACTIVE_STATUS.equals(account.status())) {
            throw invalidConfiguration("Existing development administrator is not active");
        }
        if (!account.roles().containsAll(REQUIRED_ROLES)) {
            throw invalidConfiguration("Existing account is not the bootstrapped development administrator");
        }
        if (!passwordEncoder.matches(configuredPassword, account.passwordHash())) {
            throw invalidConfiguration("Configured development administrator password does not match");
        }
    }

    private static ValidatedDevelopmentAdmin validate(DevelopmentAdminCommand command) {
        if (command == null) {
            throw invalidConfiguration("Development administrator configuration is required");
        }
        String username = AccountInputPolicy.canonicalUsername(command.username())
                .orElseThrow(() -> invalidConfiguration(
                        "Development administrator username must contain 4 to 32 letters, digits, or underscores"
                ));
        String nickname = AccountInputPolicy.normalizedNickname(command.nickname())
                .orElseThrow(() -> invalidConfiguration(
                        "Development administrator nickname must contain 1 to 64 characters"
                ));
        if (!AccountInputPolicy.hasValidPasswordCharacterLength(command.password())) {
            throw invalidConfiguration("Development administrator password must contain 8 to 64 characters");
        }
        if (!AccountInputPolicy.fitsBcryptByteLimit(command.password())) {
            throw invalidConfiguration("Development administrator password must not exceed 72 UTF-8 bytes");
        }
        return new ValidatedDevelopmentAdmin(username, command.password(), nickname);
    }

    private static IllegalStateException invalidConfiguration(String message) {
        return new IllegalStateException(message);
    }

    private static IllegalStateException invalidConfiguration(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }

    private record ValidatedDevelopmentAdmin(String username, String password, String nickname) {
        @Override
        public String toString() {
            return "ValidatedDevelopmentAdmin[username=" + username
                    + ", password=[REDACTED]"
                    + ", nickname=" + nickname + "]";
        }
    }
}

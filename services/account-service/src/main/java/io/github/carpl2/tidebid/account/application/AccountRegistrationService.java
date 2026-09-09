package io.github.carpl2.tidebid.account.application;

import io.github.carpl2.tidebid.account.application.port.AccountRegistrationStore;
import io.github.carpl2.tidebid.account.application.port.AccountRegistrationStore.RegistrationData;
import io.github.carpl2.tidebid.account.application.port.DuplicateUsernameException;
import io.github.carpl2.tidebid.account.domain.AccountErrorCode;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Service
@Profile({"local-db", "nacos"})
public class AccountRegistrationService {

    private static final BigDecimal INITIAL_AVAILABLE_BALANCE = new BigDecimal("10000.00");
    private static final BigDecimal INITIAL_FROZEN_BALANCE = new BigDecimal("0.00");

    private final PasswordEncoder passwordEncoder;
    private final AccountRegistrationStore registrationStore;

    public AccountRegistrationService(
            PasswordEncoder passwordEncoder,
            AccountRegistrationStore registrationStore
    ) {
        this.passwordEncoder = passwordEncoder;
        this.registrationStore = registrationStore;
    }

    public RegisteredAccount register(RegisterAccountCommand command) {
        if (command == null) {
            throw invalid("Registration request is required");
        }

        validateRequestId(command.requestId());
        String canonicalUsername = normalizeUsername(command.username());
        String nickname = normalizeNickname(command.nickname());
        validatePassword(command.password());

        if (registrationStore.usernameExists(canonicalUsername)) {
            throw new BusinessException(AccountErrorCode.USERNAME_ALREADY_EXISTS);
        }

        String passwordHash = passwordEncoder.encode(command.password());
        RegistrationData registrationData = new RegistrationData(
                canonicalUsername,
                passwordHash,
                nickname,
                INITIAL_AVAILABLE_BALANCE,
                INITIAL_FROZEN_BALANCE
        );

        try {
            long userId = registrationStore.create(registrationData);
            return new RegisteredAccount(userId, canonicalUsername, nickname, Set.of("USER"));
        } catch (DuplicateUsernameException exception) {
            throw new BusinessException(AccountErrorCode.USERNAME_ALREADY_EXISTS);
        }
    }

    private static void validateRequestId(String requestId) {
        if (!AccountInputPolicy.isValidRequestId(requestId)) {
            throw invalid("X-Request-Id must contain 8 to 48 letters, digits, underscores, or hyphens");
        }
    }

    private static String normalizeUsername(String username) {
        return AccountInputPolicy.canonicalUsername(username)
                .orElseThrow(() -> invalid("Username must contain 4 to 32 letters, digits, or underscores"));
    }

    private static String normalizeNickname(String nickname) {
        if (nickname == null) {
            throw invalid("Nickname is required");
        }
        String normalized = nickname.strip();
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw invalid("Nickname must contain 1 to 64 characters");
        }
        return normalized;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw invalid("Password must contain 8 to 64 characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalid("Password must not exceed 72 UTF-8 bytes");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.INVALID_ARGUMENT, message);
    }
}

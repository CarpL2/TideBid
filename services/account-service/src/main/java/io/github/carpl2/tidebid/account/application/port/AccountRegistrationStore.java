package io.github.carpl2.tidebid.account.application.port;

import java.math.BigDecimal;
import java.util.Objects;

public interface AccountRegistrationStore {

    boolean usernameExists(String canonicalUsername);

    long create(RegistrationData registrationData);

    record RegistrationData(
            String canonicalUsername,
            String passwordHash,
            String nickname,
            BigDecimal initialAvailableBalance,
            BigDecimal initialFrozenBalance
    ) {
        public RegistrationData {
            Objects.requireNonNull(canonicalUsername, "canonicalUsername must not be null");
            Objects.requireNonNull(passwordHash, "passwordHash must not be null");
            Objects.requireNonNull(nickname, "nickname must not be null");
            Objects.requireNonNull(initialAvailableBalance, "initialAvailableBalance must not be null");
            Objects.requireNonNull(initialFrozenBalance, "initialFrozenBalance must not be null");
        }

        @Override
        public String toString() {
            return "RegistrationData[canonicalUsername=" + canonicalUsername
                    + ", passwordHash=[REDACTED]"
                    + ", nickname=" + nickname
                    + ", initialAvailableBalance=" + initialAvailableBalance
                    + ", initialFrozenBalance=" + initialFrozenBalance + "]";
        }
    }
}

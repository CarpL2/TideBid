package io.github.carpl2.tidebid.account.infrastructure.security;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InternalServiceTokenPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void requiresAHighEntropyLengthToken() {
        assertThat(validator.validate(new InternalServiceTokenProperties(null))).isNotEmpty();
        assertThat(validator.validate(new InternalServiceTokenProperties("short-token"))).isNotEmpty();
        assertThat(validator.validate(new InternalServiceTokenProperties("x".repeat(32)))).isEmpty();
    }

    @Test
    void diagnosticStringNeverRevealsToken() {
        String token = "internal-service-token-for-tests-123456";

        assertThat(new InternalServiceTokenProperties(token).toString())
                .contains("[REDACTED]")
                .doesNotContain(token);
    }
}

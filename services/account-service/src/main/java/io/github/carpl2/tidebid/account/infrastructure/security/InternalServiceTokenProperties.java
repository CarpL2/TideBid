package io.github.carpl2.tidebid.account.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("tidebid.internal-service")
public record InternalServiceTokenProperties(
        @NotBlank
        @Size(min = 32, max = 512)
        String token
) {
    @Override
    public String toString() {
        return "InternalServiceTokenProperties[token=[REDACTED]]";
    }
}

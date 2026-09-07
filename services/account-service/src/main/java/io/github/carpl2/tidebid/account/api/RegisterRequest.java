package io.github.carpl2.tidebid.account.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Size(min = 4, max = 32)
        @Pattern(regexp = "[A-Za-z0-9_]+", message = "must contain only letters, digits, or underscores")
        String username,

        @NotBlank
        @Size(min = 8, max = 64)
        String password,

        @NotBlank
        String nickname
) {
    @Override
    public String toString() {
        return "RegisterRequest[username=" + username
                + ", password=[REDACTED]"
                + ", nickname=" + nickname + "]";
    }
}

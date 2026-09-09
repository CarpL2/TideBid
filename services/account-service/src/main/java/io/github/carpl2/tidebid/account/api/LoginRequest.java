package io.github.carpl2.tidebid.account.api;

import jakarta.validation.constraints.NotNull;

public record LoginRequest(
        @NotNull String username,
        @NotNull String password
) {
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=[REDACTED]]";
    }
}

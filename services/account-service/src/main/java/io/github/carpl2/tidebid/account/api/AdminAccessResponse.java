package io.github.carpl2.tidebid.account.api;

import io.github.carpl2.tidebid.account.application.CurrentAccount;

import java.util.List;

public record AdminAccessResponse(
        long userId,
        String username,
        List<String> roles
) {
    static AdminAccessResponse from(CurrentAccount account) {
        List<String> roles = account.roles().stream()
                .map(Enum::name)
                .sorted()
                .toList();
        return new AdminAccessResponse(account.userId(), account.username(), roles);
    }
}

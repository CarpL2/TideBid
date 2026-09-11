package io.github.carpl2.tidebid.account.api;

import io.github.carpl2.tidebid.account.application.CurrentAccount;

import java.util.List;

public record CurrentAccountResponse(
        String userId,
        String username,
        String nickname,
        List<String> roles
) {
    static CurrentAccountResponse from(CurrentAccount account) {
        List<String> roles = account.roles().stream()
                .map(Enum::name)
                .sorted()
                .toList();
        return new CurrentAccountResponse(
                Long.toString(account.userId()),
                account.username(),
                account.nickname(),
                roles
        );
    }
}

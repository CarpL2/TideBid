package io.github.carpl2.tidebid.account.api;

import io.github.carpl2.tidebid.account.application.RegisteredAccount;

import java.util.Set;

public record RegisteredUserResponse(
        long userId,
        String username,
        String nickname,
        Set<String> roles
) {
    static RegisteredUserResponse from(RegisteredAccount account) {
        return new RegisteredUserResponse(
                account.userId(),
                account.username(),
                account.nickname(),
                account.roles()
        );
    }
}

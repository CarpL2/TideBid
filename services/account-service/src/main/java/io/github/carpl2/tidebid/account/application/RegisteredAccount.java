package io.github.carpl2.tidebid.account.application;

import java.util.Set;

public record RegisteredAccount(
        long userId,
        String username,
        String nickname,
        Set<String> roles
) {
    public RegisteredAccount {
        roles = Set.copyOf(roles);
    }
}

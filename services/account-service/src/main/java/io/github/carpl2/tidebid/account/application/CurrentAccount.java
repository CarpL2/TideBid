package io.github.carpl2.tidebid.account.application;

import io.github.carpl2.tidebid.security.Role;

import java.util.Set;

public record CurrentAccount(
        long userId,
        String username,
        String nickname,
        Set<Role> roles
) {
    public CurrentAccount {
        roles = Set.copyOf(roles);
    }
}

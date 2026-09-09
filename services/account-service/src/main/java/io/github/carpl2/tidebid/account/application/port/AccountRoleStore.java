package io.github.carpl2.tidebid.account.application.port;

import io.github.carpl2.tidebid.security.Role;

public interface AccountRoleStore {

    void addRole(long userId, Role role);
}

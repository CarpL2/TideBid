package io.github.carpl2.tidebid.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedUserTest {

    @Test
    void copiesRolesAndChecksAuthorization() {
        Set<Role> mutableRoles = new HashSet<>(Set.of(Role.USER));
        AuthenticatedUser user = new AuthenticatedUser(42L, "alice", mutableRoles);

        mutableRoles.add(Role.ADMIN);

        assertThat(user.hasRole(Role.USER)).isTrue();
        assertThat(user.hasRole(Role.ADMIN)).isFalse();
        assertThatThrownBy(() -> user.roles().add(Role.ADMIN))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

package io.github.carpl2.tidebid.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtClaimsTest {

    @Test
    void rejectsNonIncreasingLifetime() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> new JwtClaims("alice", 1L, Set.of(Role.USER), now, now, "token-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expiresAt must be after issuedAt");
    }
}

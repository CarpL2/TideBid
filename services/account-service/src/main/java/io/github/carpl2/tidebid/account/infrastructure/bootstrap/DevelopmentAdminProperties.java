package io.github.carpl2.tidebid.account.infrastructure.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tidebid.development-admin")
public record DevelopmentAdminProperties(
        boolean enabled,
        String username,
        String password,
        String nickname
) {
    @Override
    public String toString() {
        return "DevelopmentAdminProperties[enabled=" + enabled
                + ", username=" + username
                + ", password=[REDACTED]"
                + ", nickname=" + nickname + "]";
    }
}

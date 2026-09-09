package io.github.carpl2.tidebid.account.application;

public record DevelopmentAdminCommand(
        String username,
        String password,
        String nickname
) {
    @Override
    public String toString() {
        return "DevelopmentAdminCommand[username=" + username
                + ", password=[REDACTED]"
                + ", nickname=" + nickname + "]";
    }
}

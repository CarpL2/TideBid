package io.github.carpl2.tidebid.account.application;

public record RegisterAccountCommand(
        String requestId,
        String username,
        String password,
        String nickname
) {
    @Override
    public String toString() {
        return "RegisterAccountCommand[requestId=" + requestId
                + ", username=" + username
                + ", password=[REDACTED]"
                + ", nickname=" + nickname + "]";
    }
}

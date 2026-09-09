package io.github.carpl2.tidebid.account.application;

public record AuthenticateAccountCommand(
        String requestId,
        String username,
        String password
) {
    @Override
    public String toString() {
        return "AuthenticateAccountCommand[requestId=" + requestId
                + ", username=" + username
                + ", password=[REDACTED]]";
    }
}

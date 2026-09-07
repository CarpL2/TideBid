package io.github.carpl2.tidebid.account.application.port;

public final class DuplicateUsernameException extends RuntimeException {

    public DuplicateUsernameException(Throwable cause) {
        super("A duplicate canonical username was rejected", cause);
    }
}

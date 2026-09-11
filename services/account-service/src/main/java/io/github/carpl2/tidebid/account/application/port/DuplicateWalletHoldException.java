package io.github.carpl2.tidebid.account.application.port;

public final class DuplicateWalletHoldException extends RuntimeException {

    public DuplicateWalletHoldException(Throwable cause) {
        super("Wallet hold number already exists", cause);
    }
}

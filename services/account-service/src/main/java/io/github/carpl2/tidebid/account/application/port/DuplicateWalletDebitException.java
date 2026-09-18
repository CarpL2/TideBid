package io.github.carpl2.tidebid.account.application.port;

public final class DuplicateWalletDebitException extends RuntimeException {
    public DuplicateWalletDebitException(Throwable cause) {
        super(cause);
    }
}

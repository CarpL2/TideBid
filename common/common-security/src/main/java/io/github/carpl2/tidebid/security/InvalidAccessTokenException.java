package io.github.carpl2.tidebid.security;

/**
 * Signals an invalid access token without exposing the encoded token in the exception message.
 */
public final class InvalidAccessTokenException extends RuntimeException {

    public InvalidAccessTokenException() {
        super("Access token is invalid");
    }

}

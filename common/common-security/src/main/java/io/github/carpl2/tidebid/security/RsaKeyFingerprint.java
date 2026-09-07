package io.github.carpl2.tidebid.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Objects;

/**
 * Produces a stable, non-secret key identifier from an RSA public key.
 */
public final class RsaKeyFingerprint {

    private RsaKeyFingerprint() {
    }

    public static String keyId(RSAPublicKey publicKey) {
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}

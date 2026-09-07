package io.github.carpl2.tidebid.security;

import java.security.InvalidKeyException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Objects;

/**
 * A validated RSA public/private key pair used for JWT signing.
 */
public record RsaKeyPairMaterial(RSAPublicKey publicKey, RSAPrivateKey privateKey) {

    private static final int MINIMUM_MODULUS_BITS = 2048;

    public RsaKeyPairMaterial {
        publicKey = Objects.requireNonNull(publicKey, "publicKey must not be null");
        privateKey = Objects.requireNonNull(privateKey, "privateKey must not be null");
        try {
            validate(publicKey, privateKey);
        } catch (InvalidKeyException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    private static void validate(RSAPublicKey publicKey, RSAPrivateKey privateKey) throws InvalidKeyException {
        if (publicKey.getModulus().bitLength() < MINIMUM_MODULUS_BITS) {
            throw new InvalidKeyException("RSA public key must contain at least 2048 bits");
        }
        if (!publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new InvalidKeyException("RSA public and private keys do not belong to the same key pair");
        }
        if (privateKey instanceof RSAPrivateCrtKey crtKey
                && !publicKey.getPublicExponent().equals(crtKey.getPublicExponent())) {
            throw new InvalidKeyException("RSA public and private keys use different public exponents");
        }
    }
}

package io.github.carpl2.tidebid.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RsaPemKeyLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsPkcs8PrivateAndX509PublicPemFiles() throws Exception {
        KeyPair pair = generateKeyPair();
        Path privateKey = writePem("jwt-private.pem", "PRIVATE KEY", pair.getPrivate().getEncoded());
        Path publicKey = writePem("jwt-public.pem", "PUBLIC KEY", pair.getPublic().getEncoded());

        RsaKeyPairMaterial loaded = RsaPemKeyLoader.loadKeyPair(privateKey, publicKey);

        assertThat(loaded.publicKey().getModulus()).isEqualTo(((RSAPublicKey) pair.getPublic()).getModulus());
        assertThat(loaded.privateKey().getModulus()).isEqualTo(((RSAPrivateKey) pair.getPrivate()).getModulus());
        assertThat(RsaKeyFingerprint.keyId(loaded.publicKey())).hasSize(43);
    }

    @Test
    void rejectsMismatchedKeyPairs() throws Exception {
        KeyPair first = generateKeyPair();
        KeyPair second = generateKeyPair();
        Path privateKey = writePem("jwt-private.pem", "PRIVATE KEY", first.getPrivate().getEncoded());
        Path publicKey = writePem("jwt-public.pem", "PUBLIC KEY", second.getPublic().getEncoded());

        assertThatThrownBy(() -> RsaPemKeyLoader.loadKeyPair(privateKey, publicKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not belong to the same key pair");
    }

    @Test
    void rejectsUnexpectedPemFormat() throws Exception {
        Path publicKey = temporaryDirectory.resolve("jwt-public.pem");
        Files.writeString(
                publicKey,
                "-----BEGIN RSA PUBLIC KEY-----\nAAAA\n-----END RSA PUBLIC KEY-----\n",
                StandardCharsets.US_ASCII
        );

        assertThatThrownBy(() -> RsaPemKeyLoader.loadPublicKey(publicKey))
                .isInstanceOf(java.security.spec.InvalidKeySpecException.class)
                .hasMessageContaining("Expected an unencrypted PUBLIC KEY PEM file");
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private Path writePem(String fileName, String label, byte[] encoded) throws Exception {
        String payload = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded);
        Path path = temporaryDirectory.resolve(fileName);
        Files.writeString(
                path,
                "-----BEGIN " + label + "-----\n" + payload + "\n-----END " + label + "-----\n",
                StandardCharsets.US_ASCII
        );
        return path;
    }
}

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;

/**
 * JDK-only helper invoked by generate-jwt-keys.ps1.
 */
public final class JwtKeyGenerator {

    private static final int RSA_KEY_SIZE = 3072;
    private static final byte[] PAIR_PROBE = "TideBid JWT key-pair validation".getBytes(StandardCharsets.UTF_8);

    private JwtKeyGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 21) {
            throw new IllegalStateException("Java 21 is required; found Java " + Runtime.version().feature());
        }
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: JwtKeyGenerator.java <private-key-path> <public-key-path> [--force]"
            );
        }

        Path privateKeyPath = Path.of(args[0]).toAbsolutePath().normalize();
        Path publicKeyPath = Path.of(args[1]).toAbsolutePath().normalize();
        boolean force = args.length == 3 && "--force".equals(args[2]);
        if (args.length == 3 && !force) {
            throw new IllegalArgumentException("The only supported option is --force");
        }
        if (privateKeyPath.equals(publicKeyPath)) {
            throw new IllegalArgumentException("Private and public key paths must be different");
        }

        boolean privateExists = Files.exists(privateKeyPath);
        boolean publicExists = Files.exists(publicKeyPath);
        if (!force && privateExists && publicExists) {
            validateExistingPair(privateKeyPath, publicKeyPath);
            System.out.println("Existing RS256 development key pair is valid; no files were changed.");
            return;
        }
        if (!force && privateExists != publicExists) {
            throw new IllegalStateException(
                    "Only one JWT key file exists. Restore its matching file or rerun explicitly with -Force."
            );
        }

        Path privateParent = requireParent(privateKeyPath);
        Path publicParent = requireParent(publicKeyPath);
        Files.createDirectories(privateParent);
        Files.createDirectories(publicParent);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(RSA_KEY_SIZE);
        KeyPair keyPair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        validatePair(publicKey, privateKey);

        Path temporaryPrivate = Files.createTempFile(privateParent, ".jwt-private-", ".tmp");
        Path temporaryPublic = Files.createTempFile(publicParent, ".jwt-public-", ".tmp");
        try {
            Files.writeString(
                    temporaryPrivate,
                    toPem("PRIVATE KEY", privateKey.getEncoded()),
                    StandardCharsets.US_ASCII
            );
            restrictPrivateKeyPermissions(temporaryPrivate);
            Files.writeString(
                    temporaryPublic,
                    toPem("PUBLIC KEY", publicKey.getEncoded()),
                    StandardCharsets.US_ASCII
            );
            moveIntoPlace(temporaryPrivate, privateKeyPath, force);
            moveIntoPlace(temporaryPublic, publicKeyPath, force);
        } finally {
            Files.deleteIfExists(temporaryPrivate);
            Files.deleteIfExists(temporaryPublic);
        }

        validateExistingPair(privateKeyPath, publicKeyPath);
        System.out.println("Generated and validated a 3072-bit RS256 development key pair.");
        System.out.println("Private key: " + privateKeyPath);
        System.out.println("Public key:  " + publicKeyPath);
    }

    private static Path requireParent(Path path) {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("JWT key path must have a parent directory: " + path);
        }
        return parent;
    }

    private static void validateExistingPair(Path privateKeyPath, Path publicKeyPath) throws Exception {
        RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(readPem(privateKeyPath, "PRIVATE KEY"))
        );
        RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(readPem(publicKeyPath, "PUBLIC KEY"))
        );
        validatePair(publicKey, privateKey);
    }

    private static void validatePair(RSAPublicKey publicKey, RSAPrivateKey privateKey) throws Exception {
        if (publicKey.getModulus().bitLength() < RSA_KEY_SIZE) {
            throw new IllegalArgumentException("Development RSA key must contain at least 3072 bits");
        }
        if (!publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalArgumentException("JWT public and private keys do not belong to the same pair");
        }
        if (privateKey instanceof RSAPrivateCrtKey crtKey
                && !publicKey.getPublicExponent().equals(crtKey.getPublicExponent())) {
            throw new IllegalArgumentException("JWT public and private keys use different public exponents");
        }

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(PAIR_PROBE);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(PAIR_PROBE);
        if (!verifier.verify(signature)) {
            throw new IllegalArgumentException("JWT public and private keys failed a signing probe");
        }
    }

    private static byte[] readPem(Path path, String label) throws IOException {
        String pem = Files.readString(path, StandardCharsets.US_ASCII);
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        if (!pem.contains(begin) || !pem.contains(end)) {
            throw new IllegalArgumentException("Expected an unencrypted " + label + " PEM file: " + path);
        }
        String payload = pem.replace(begin, "").replace(end, "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(payload);
    }

    private static String toPem(String label, byte[] encoded) {
        String payload = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded);
        return "-----BEGIN " + label + "-----\n" + payload + "\n-----END " + label + "-----\n";
    }

    private static void restrictPrivateKeyPermissions(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        }
    }

    private static void moveIntoPlace(Path source, Path target, boolean replace) throws IOException {
        StandardCopyOption[] atomicOptions = replace
                ? new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING}
                : new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE};
        StandardCopyOption[] fallbackOptions = replace
                ? new StandardCopyOption[]{StandardCopyOption.REPLACE_EXISTING}
                : new StandardCopyOption[]{};
        try {
            Files.move(source, target, atomicOptions);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, fallbackOptions);
        }
    }
}

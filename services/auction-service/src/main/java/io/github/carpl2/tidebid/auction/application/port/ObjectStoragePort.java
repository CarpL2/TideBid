package io.github.carpl2.tidebid.auction.application.port;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Application-facing operations needed from private object storage.
 *
 * <p>The port deliberately exposes object keys and short-lived signed URLs only. It never exposes
 * provider credentials or an OSS client to the application layer.</p>
 */
public interface ObjectStoragePort {

    SignedUpload signUpload(UploadSigningRequest request);

    Optional<StoredObjectMetadata> headObject(String objectKey);

    void deleteControlledObject(String objectKey);

    SignedRead signRead(ReadSigningRequest request);

    static String requireControlledObjectKey(String value) {
        String normalized = requireText(value, 512, "objectKey");
        if (normalized.startsWith("/") || normalized.contains("\\") || normalized.contains("//")) {
            throw new IllegalArgumentException("objectKey must be a normalized relative key");
        }
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("objectKey contains an unsafe path segment");
            }
        }
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")) {
            throw new IllegalArgumentException("objectKey contains unsupported characters");
        }
        return normalized;
    }

    record UploadSigningRequest(
            String objectKey,
            String contentType,
            long contentLength,
            String checksumSha256,
            Instant expiresAt
    ) {
        private static final Pattern SHA256_PATTERN = Pattern.compile("[a-f0-9]{64}");

        public UploadSigningRequest {
            objectKey = requireControlledObjectKey(objectKey);
            contentType = requireContentType(contentType);
            if (contentLength <= 0) {
                throw new IllegalArgumentException("contentLength must be positive");
            }
            checksumSha256 = normalizeOptionalChecksum(checksumSha256, SHA256_PATTERN);
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }
    }

    record SignedUpload(URI url, Map<String, String> requiredHeaders, Instant expiresAt) {
        public SignedUpload {
            url = requireHttpUrl(url);
            requiredHeaders = copyHeaders(requiredHeaders);
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }

        @Override
        public String toString() {
            return "SignedUpload[url=[REDACTED], requiredHeaderNames="
                    + requiredHeaders.keySet() + ", expiresAt=" + expiresAt + "]";
        }
    }

    record StoredObjectMetadata(
            String objectKey,
            String contentType,
            long contentLength,
            String checksumSha256
    ) {
        private static final Pattern SHA256_PATTERN = Pattern.compile("[a-f0-9]{64}");

        public StoredObjectMetadata {
            objectKey = requireControlledObjectKey(objectKey);
            contentType = requireContentType(contentType);
            if (contentLength <= 0) {
                throw new IllegalArgumentException("contentLength must be positive");
            }
            checksumSha256 = normalizeOptionalChecksum(checksumSha256, SHA256_PATTERN);
        }
    }

    record ReadSigningRequest(String objectKey, Instant expiresAt) {
        public ReadSigningRequest {
            objectKey = requireControlledObjectKey(objectKey);
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }
    }

    record SignedRead(URI url, Instant expiresAt) {
        public SignedRead {
            url = requireHttpUrl(url);
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }

        @Override
        public String toString() {
            return "SignedRead[url=[REDACTED], expiresAt=" + expiresAt + "]";
        }
    }

    private static String requireContentType(String value) {
        String normalized = requireText(value, 64, "contentType").toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")) {
            throw new IllegalArgumentException("contentType has an invalid format");
        }
        return normalized;
    }

    private static String normalizeOptionalChecksum(String value, Pattern pattern) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException("checksumSha256 must contain 64 hexadecimal characters");
        }
        return normalized;
    }

    private static URI requireHttpUrl(URI value) {
        URI url = Objects.requireNonNull(value, "url must not be null");
        if (!url.isAbsolute() || url.getHost() == null
                || !("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))) {
            throw new IllegalArgumentException("url must be an absolute HTTP(S) URI");
        }
        return url;
    }

    private static Map<String, String> copyHeaders(Map<String, String> value) {
        Objects.requireNonNull(value, "requiredHeaders must not be null");
        Map<String, String> copy = new LinkedHashMap<>();
        value.forEach((name, headerValue) -> {
            String normalizedName = requireText(name, 128, "header name");
            String normalizedValue = requireText(headerValue, 1024, "header value");
            if (normalizedName.indexOf('\r') >= 0 || normalizedName.indexOf('\n') >= 0
                    || normalizedValue.indexOf('\r') >= 0 || normalizedValue.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("signed headers must not contain line breaks");
            }
            copy.put(normalizedName, normalizedValue);
        });
        return Map.copyOf(copy);
    }

    private static String requireText(String value, int maximumLength, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " has an invalid length");
        }
        return normalized;
    }
}

package io.github.carpl2.tidebid.auction.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

@ConfigurationProperties("tidebid.auction.storage")
public record AuctionStorageProperties(
        boolean enabled,
        String endpoint,
        String region,
        String bucket,
        String accessKeyId,
        String accessKeySecret,
        Duration uploadUrlTtl,
        Duration readUrlTtl,
        Duration pendingRetention
) {

    private static final Duration MINIMUM_URL_TTL = Duration.ofMinutes(1);
    private static final Duration MAXIMUM_URL_TTL = Duration.ofHours(1);
    private static final Duration MAXIMUM_PENDING_RETENTION = Duration.ofDays(30);
    private static final Pattern REGION_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern BUCKET_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]");

    public AuctionStorageProperties {
        endpoint = normalize(endpoint);
        region = normalize(region);
        bucket = normalize(bucket);
        accessKeyId = normalize(accessKeyId);
        accessKeySecret = normalize(accessKeySecret);
        uploadUrlTtl = Objects.requireNonNull(uploadUrlTtl, "uploadUrlTtl must not be null");
        readUrlTtl = Objects.requireNonNull(readUrlTtl, "readUrlTtl must not be null");
        pendingRetention = Objects.requireNonNull(pendingRetention, "pendingRetention must not be null");

        requireUrlTtl(uploadUrlTtl, "uploadUrlTtl");
        requireUrlTtl(readUrlTtl, "readUrlTtl");
        if (pendingRetention.compareTo(uploadUrlTtl) < 0
                || pendingRetention.compareTo(MAXIMUM_PENDING_RETENTION) > 0) {
            throw new IllegalArgumentException("pendingRetention must be between uploadUrlTtl and 30 days");
        }

        if (enabled) {
            requireText(endpoint, "endpoint");
            requireHttpEndpoint(endpoint);
            requireMatching(region, REGION_PATTERN, "region");
            requireMatching(bucket, BUCKET_PATTERN, "bucket");
            requireText(accessKeyId, "accessKeyId");
            requireText(accessKeySecret, "accessKeySecret");
        }
    }

    @Override
    public String toString() {
        return "AuctionStorageProperties[enabled=" + enabled
                + ", endpoint=" + endpoint
                + ", region=" + region
                + ", bucket=" + bucket
                + ", accessKeyId=[REDACTED]"
                + ", accessKeySecret=[REDACTED]"
                + ", uploadUrlTtl=" + uploadUrlTtl
                + ", readUrlTtl=" + readUrlTtl
                + ", pendingRetention=" + pendingRetention + "]";
    }

    private static void requireUrlTtl(Duration value, String name) {
        if (value.compareTo(MINIMUM_URL_TTL) < 0 || value.compareTo(MAXIMUM_URL_TTL) > 0) {
            throw new IllegalArgumentException(name + " must be between 1 minute and 1 hour");
        }
    }

    private static void requireHttpEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URI", exception);
        }
        if (!uri.isAbsolute() || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URI");
        }
    }

    private static void requireMatching(String value, Pattern pattern, String name) {
        requireText(value, name);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
    }

    private static void requireText(String value, String name) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank when storage is enabled");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

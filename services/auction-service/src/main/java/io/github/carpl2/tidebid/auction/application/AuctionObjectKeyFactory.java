package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Component
public class AuctionObjectKeyFactory {

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    public String create(String prefix, long ownerId, String extension, Instant now) {
        String normalizedPrefix = requireSegment(prefix, 32, "prefix");
        if (ownerId <= 0) {
            throw new IllegalArgumentException("ownerId must be positive");
        }
        String normalizedExtension = requireSegment(extension, 8, "extension");
        String month = Objects.requireNonNull(now, "now must not be null")
                .atZone(ZoneOffset.UTC)
                .format(MONTH_FORMAT);
        String key = "%s/users/%d/%s/%s.%s".formatted(
                normalizedPrefix,
                ownerId,
                month,
                UUID.randomUUID(),
                normalizedExtension
        );
        return ObjectStoragePort.requireControlledObjectKey(key);
    }

    private static String requireSegment(String value, int maximumLength, String name) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > maximumLength
                || !normalized.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return normalized;
    }
}

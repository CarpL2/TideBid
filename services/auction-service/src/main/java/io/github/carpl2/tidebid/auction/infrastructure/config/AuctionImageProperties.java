package io.github.carpl2.tidebid.auction.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties("tidebid.auction.image")
public record AuctionImageProperties(
        Set<String> allowedContentTypes,
        DataSize maxSize,
        int maxImagesPerItem
) {

    private static final DataSize MAXIMUM_IMAGE_SIZE = DataSize.ofMegabytes(100);

    public AuctionImageProperties {
        Objects.requireNonNull(allowedContentTypes, "allowedContentTypes must not be null");
        allowedContentTypes = allowedContentTypes.stream()
                .map(AuctionImageProperties::normalizeContentType)
                .collect(Collectors.toUnmodifiableSet());
        if (allowedContentTypes.isEmpty()) {
            throw new IllegalArgumentException("allowedContentTypes must not be empty");
        }
        maxSize = Objects.requireNonNull(maxSize, "maxSize must not be null");
        if (maxSize.toBytes() <= 0 || maxSize.compareTo(MAXIMUM_IMAGE_SIZE) > 0) {
            throw new IllegalArgumentException("maxSize must be between 1 byte and 100MB");
        }
        if (maxImagesPerItem < 1 || maxImagesPerItem > 20) {
            throw new IllegalArgumentException("maxImagesPerItem must be between 1 and 20");
        }
    }

    private static String normalizeContentType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("image/[a-z0-9.+-]+")) {
            throw new IllegalArgumentException("allowedContentTypes must contain valid image MIME types");
        }
        return normalized;
    }
}

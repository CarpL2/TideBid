package io.github.carpl2.tidebid.auction.domain;

import java.time.Instant;
import java.util.Objects;

public record AuctionItemImage(
        long id,
        Long itemId,
        long ownerId,
        String objectKey,
        String originalFilename,
        String contentType,
        long contentLength,
        String contentSha256,
        Integer sortOrder,
        AuctionImageStatus storageStatus,
        Instant uploadExpiresAt,
        Instant createdAt,
        Instant updatedAt
) {
    public AuctionItemImage {
        AuctionDomainRules.positiveId(id, "id");
        if (itemId != null) {
            AuctionDomainRules.positiveId(itemId, "itemId");
        }
        AuctionDomainRules.positiveId(ownerId, "ownerId");
        objectKey = AuctionDomainRules.text(objectKey, 1, 512, "objectKey");
        originalFilename = AuctionDomainRules.text(originalFilename, 1, 255, "originalFilename");
        contentType = AuctionDomainRules.text(contentType, 1, 64, "contentType");
        if (contentLength <= 0) {
            throw new IllegalArgumentException("contentLength must be positive");
        }
        contentSha256 = AuctionDomainRules.optionalSha256(contentSha256);
        if (sortOrder != null && (sortOrder < 0 || sortOrder > 8)) {
            throw new IllegalArgumentException("sortOrder must be between 0 and 8");
        }
        Objects.requireNonNull(storageStatus, "storageStatus must not be null");
        boolean boundShape = itemId != null && sortOrder != null;
        if ((storageStatus == AuctionImageStatus.BOUND) != boundShape) {
            throw new IllegalArgumentException("image binding fields do not match storageStatus");
        }
        uploadExpiresAt = AuctionDomainRules.instant(uploadExpiresAt, "uploadExpiresAt");
        createdAt = AuctionDomainRules.instant(createdAt, "createdAt");
        updatedAt = AuctionDomainRules.instant(updatedAt, "updatedAt");
        if (!uploadExpiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("uploadExpiresAt must be after createdAt");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}

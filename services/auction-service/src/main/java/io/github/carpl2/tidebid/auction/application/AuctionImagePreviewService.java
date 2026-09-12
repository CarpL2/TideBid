package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionImageStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionStorageProperties;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Creates short-lived read URLs only after the application has authorized the stored image. */
@Service
@Profile({"local-db", "nacos"})
public class AuctionImagePreviewService {

    private final AuctionItemRepository itemRepository;
    private final ObjectStoragePort objectStorage;
    private final AuctionStorageProperties storageProperties;
    private final Clock clock;

    public AuctionImagePreviewService(
            AuctionItemRepository itemRepository,
            ObjectStoragePort objectStorage,
            AuctionStorageProperties storageProperties,
            Clock clock
    ) {
        this.itemRepository = itemRepository;
        this.objectStorage = objectStorage;
        this.storageProperties = storageProperties;
        this.clock = clock;
    }

    public ImagePreview createOwnerPreview(long ownerId, String objectKey) {
        if (ownerId <= 0) {
            throw imageInvalid("ownerId must be positive");
        }

        final String controlledObjectKey;
        try {
            controlledObjectKey = ObjectStoragePort.requireControlledObjectKey(objectKey);
        } catch (IllegalArgumentException exception) {
            throw imageInvalid(exception.getMessage());
        }

        AuctionItemImage image = itemRepository.findImageByObjectKey(controlledObjectKey)
                .orElseThrow(() -> imageInvalid("Image does not exist"));
        if (image.ownerId() != ownerId) {
            throw new BusinessException(AuctionErrorCode.ASSET_ACCESS_DENIED);
        }
        if (image.storageStatus() == AuctionImageStatus.EXPIRED) {
            throw imageInvalid("Image has expired");
        }

        Instant expiresAt = clock.instant()
                .truncatedTo(ChronoUnit.MICROS)
                .plus(storageProperties.readUrlTtl());
        ObjectStoragePort.SignedRead signedRead = objectStorage.signRead(
                new ObjectStoragePort.ReadSigningRequest(controlledObjectKey, expiresAt)
        );
        return new ImagePreview(controlledObjectKey, signedRead.url(), signedRead.expiresAt());
    }

    private static BusinessException imageInvalid(String message) {
        return new BusinessException(AuctionErrorCode.IMAGE_INVALID, message);
    }

    public record ImagePreview(String objectKey, URI url, Instant expiresAt) {
        public ImagePreview {
            objectKey = ObjectStoragePort.requireControlledObjectKey(objectKey);
            url = Objects.requireNonNull(url, "url must not be null");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }

        @Override
        public String toString() {
            return "ImagePreview[objectKey=" + objectKey
                    + ", url=[REDACTED], expiresAt=" + expiresAt + "]";
        }
    }
}

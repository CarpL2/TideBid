package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionImageStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;

@Service
@Profile({"local-db", "nacos"})
public class AuctionImageVerificationService {

    private final AuctionItemRepository itemRepository;
    private final ObjectStoragePort objectStorage;
    private final Clock clock;

    public AuctionImageVerificationService(
            AuctionItemRepository itemRepository,
            ObjectStoragePort objectStorage,
            Clock clock
    ) {
        this.itemRepository = itemRepository;
        this.objectStorage = objectStorage;
        this.clock = clock;
    }

    public VerifiedUpload verifyPendingUpload(long ownerId, String objectKey) {
        if (ownerId <= 0) {
            throw imageInvalid("ownerId must be positive");
        }

        final String controlledObjectKey;
        try {
            controlledObjectKey = ObjectStoragePort.requireControlledObjectKey(objectKey);
        } catch (IllegalArgumentException exception) {
            throw imageInvalid(exception.getMessage());
        }

        AuctionItemImage intent = itemRepository.findImageByObjectKey(controlledObjectKey)
                .orElseThrow(() -> imageInvalid("Upload intent does not exist"));
        if (intent.ownerId() != ownerId) {
            throw new BusinessException(AuctionErrorCode.ASSET_ACCESS_DENIED);
        }
        if (intent.storageStatus() != AuctionImageStatus.PENDING) {
            throw imageInvalid("Upload intent is no longer pending");
        }
        if (!clock.instant().isBefore(intent.uploadExpiresAt())) {
            throw imageInvalid("Upload intent has expired");
        }

        ObjectStoragePort.StoredObjectMetadata object = objectStorage.headObject(controlledObjectKey)
                .orElseThrow(() -> imageInvalid("Uploaded object does not exist"));
        if (!metadataMatches(intent, object)) {
            throw imageInvalid("Uploaded object metadata does not match the upload intent");
        }
        return new VerifiedUpload(intent.id(), intent.objectKey());
    }

    public VerifiedUpload verifyBoundImage(long ownerId, long itemId, AuctionItemImage image) {
        if (ownerId <= 0 || itemId <= 0) {
            throw imageInvalid("ownerId and itemId must be positive");
        }
        if (image == null) {
            throw imageInvalid("Bound image must not be null");
        }
        if (image.ownerId() != ownerId) {
            throw new BusinessException(AuctionErrorCode.ASSET_ACCESS_DENIED);
        }
        if (!Long.valueOf(itemId).equals(image.itemId())
                || image.storageStatus() != AuctionImageStatus.BOUND
                || image.sortOrder() == null) {
            throw imageInvalid("Image is not bound to this auction item");
        }

        ObjectStoragePort.StoredObjectMetadata object = objectStorage.headObject(image.objectKey())
                .orElseThrow(() -> imageInvalid("Bound image object does not exist"));
        if (!metadataMatches(image, object)) {
            throw imageInvalid("Bound image object metadata does not match the stored record");
        }
        return new VerifiedUpload(image.id(), image.objectKey());
    }

    private static boolean metadataMatches(
            AuctionItemImage image,
            ObjectStoragePort.StoredObjectMetadata object
    ) {
        return image.objectKey().equals(object.objectKey())
                && image.contentType().equals(object.contentType())
                && image.contentLength() == object.contentLength()
                && checksumMatches(image.contentSha256(), object.checksumSha256());
    }

    private static boolean checksumMatches(String expected, String actual) {
        return expected == null || Objects.equals(expected, actual);
    }

    private static BusinessException imageInvalid(String message) {
        return new BusinessException(AuctionErrorCode.IMAGE_INVALID, message);
    }

    public record VerifiedUpload(long imageId, String objectKey) {
        public VerifiedUpload {
            if (imageId <= 0) {
                throw new IllegalArgumentException("imageId must be positive");
            }
            objectKey = ObjectStoragePort.requireControlledObjectKey(objectKey);
        }
    }
}

package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.IdGenerator;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionImageStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionImageProperties;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionStorageProperties;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@Profile({"local-db", "nacos"})
public class AuctionUploadIntentService {

    private static final Map<String, Set<String>> CONTENT_TYPE_EXTENSIONS = Map.of(
            "image/jpeg", Set.of("jpg", "jpeg"),
            "image/png", Set.of("png"),
            "image/webp", Set.of("webp")
    );

    private final AuctionItemRepository itemRepository;
    private final ObjectStoragePort objectStorage;
    private final IdGenerator idGenerator;
    private final AuctionObjectKeyFactory objectKeyFactory;
    private final AuctionImageProperties imageProperties;
    private final AuctionStorageProperties storageProperties;
    private final Clock clock;

    public AuctionUploadIntentService(
            AuctionItemRepository itemRepository,
            ObjectStoragePort objectStorage,
            IdGenerator idGenerator,
            AuctionObjectKeyFactory objectKeyFactory,
            AuctionImageProperties imageProperties,
            AuctionStorageProperties storageProperties,
            Clock clock
    ) {
        this.itemRepository = itemRepository;
        this.objectStorage = objectStorage;
        this.idGenerator = idGenerator;
        this.objectKeyFactory = objectKeyFactory;
        this.imageProperties = imageProperties;
        this.storageProperties = storageProperties;
        this.clock = clock;
    }

    public UploadIntent create(UploadIntentCommand command) {
        ValidatedImage input = validate(command);
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = createdAt.plus(storageProperties.uploadUrlTtl());
        long imageId = idGenerator.nextId();
        if (imageId <= 0) {
            throw new IllegalStateException("idGenerator returned a non-positive id");
        }
        String objectKey = objectKeyFactory.create(
                storageProperties.objectKeyPrefix(),
                input.ownerId(),
                input.extension(),
                createdAt
        );

        ObjectStoragePort.SignedUpload signedUpload = objectStorage.signUpload(
                new ObjectStoragePort.UploadSigningRequest(
                        objectKey,
                        input.contentType(),
                        input.contentLength(),
                        input.checksumSha256(),
                        expiresAt
                )
        );

        AuctionItemImage pending = new AuctionItemImage(
                imageId,
                null,
                input.ownerId(),
                objectKey,
                input.originalFilename(),
                input.contentType(),
                input.contentLength(),
                input.checksumSha256(),
                null,
                AuctionImageStatus.PENDING,
                expiresAt,
                createdAt,
                createdAt
        );
        AuctionItemImage stored = itemRepository.insertImage(pending);
        return new UploadIntent(stored.id(), stored.objectKey(), signedUpload);
    }

    private ValidatedImage validate(UploadIntentCommand command) {
        try {
            Objects.requireNonNull(command, "command must not be null");
            if (command.ownerId() <= 0) {
                throw new IllegalArgumentException("ownerId must be positive");
            }
            String filename = normalizedFilename(command.originalFilename());
            String extension = extension(filename);
            String contentType = normalizedContentType(command.contentType());
            Set<String> allowedExtensions = CONTENT_TYPE_EXTENSIONS.get(contentType);
            if (!imageProperties.allowedContentTypes().contains(contentType) || allowedExtensions == null) {
                throw new IllegalArgumentException("contentType is not an allowed auction image type");
            }
            if (!allowedExtensions.contains(extension)) {
                throw new IllegalArgumentException("filename extension does not match contentType");
            }
            if (command.contentLength() <= 0
                    || command.contentLength() > imageProperties.maxSize().toBytes()) {
                throw new IllegalArgumentException("contentLength exceeds the configured image size limit");
            }
            return new ValidatedImage(
                    command.ownerId(),
                    filename,
                    extension,
                    contentType,
                    command.contentLength(),
                    normalizedChecksum(command.checksumSha256())
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(AuctionErrorCode.IMAGE_INVALID, exception.getMessage());
        }
    }

    private static String normalizedFilename(String value) {
        String filename = value == null ? "" : value.trim();
        if (filename.isEmpty() || filename.length() > 255
                || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
                || filename.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("originalFilename has an invalid format");
        }
        return filename;
    }

    private static String extension(String filename) {
        int separator = filename.lastIndexOf('.');
        if (separator <= 0 || separator == filename.length() - 1) {
            throw new IllegalArgumentException("originalFilename must contain a supported extension");
        }
        return filename.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizedContentType(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizedChecksum(String value) {
        String checksum = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (checksum.isEmpty()) {
            return null;
        }
        if (!checksum.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("checksumSha256 must contain 64 hexadecimal characters");
        }
        return checksum;
    }

    public record UploadIntentCommand(
            long ownerId,
            String originalFilename,
            String contentType,
            long contentLength,
            String checksumSha256
    ) {
    }

    public record UploadIntent(
            long imageId,
            String objectKey,
            ObjectStoragePort.SignedUpload upload
    ) {
        public UploadIntent {
            if (imageId <= 0) {
                throw new IllegalArgumentException("imageId must be positive");
            }
            objectKey = ObjectStoragePort.requireControlledObjectKey(objectKey);
            Objects.requireNonNull(upload, "upload must not be null");
        }
    }

    private record ValidatedImage(
            long ownerId,
            String originalFilename,
            String extension,
            String contentType,
            long contentLength,
            String checksumSha256
    ) {
    }
}

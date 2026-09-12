package io.github.carpl2.tidebid.auction.infrastructure.storage;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.PresignOptions;
import com.aliyun.sdk.service.oss2.exceptions.ServiceException;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.HeadObjectRequest;
import com.aliyun.sdk.service.oss2.models.HeadObjectResult;
import com.aliyun.sdk.service.oss2.models.PresignResult;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionStorageProperties;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
@ConditionalOnProperty(
        prefix = "tidebid.auction.storage",
        name = "enabled",
        havingValue = "true"
)
public class AlibabaOssObjectStorageAdapter implements ObjectStoragePort {

    static final String CHECKSUM_METADATA_KEY = "tidebid-sha256";

    private final OSSClient client;
    private final String bucket;

    public AlibabaOssObjectStorageAdapter(OSSClient client, AuctionStorageProperties properties) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.bucket = Objects.requireNonNull(properties, "properties must not be null").bucket();
    }

    @Override
    public SignedUpload signUpload(UploadSigningRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.contentLength() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("contentLength exceeds the OSS SDK request limit");
        }

        PutObjectRequest.Builder builder = PutObjectRequest.newBuilder()
                .bucket(bucket)
                .key(request.objectKey())
                .contentType(request.contentType())
                .contentLength(Math.toIntExact(request.contentLength()))
                .forbidOverwrite(true);
        if (request.checksumSha256() != null) {
            builder.metadata(Map.of(CHECKSUM_METADATA_KEY, request.checksumSha256()));
        }

        try {
            PresignResult result = client.presign(
                    builder.build(),
                    PresignOptions.newBuilder().expiration(request.expiresAt()).build()
            );
            return signedUpload(result, request.expiresAt());
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    @Override
    public Optional<StoredObjectMetadata> headObject(String objectKey) {
        String normalizedKey = ObjectStoragePort.requireControlledObjectKey(objectKey);
        try {
            HeadObjectResult result = client.headObject(HeadObjectRequest.newBuilder()
                    .bucket(bucket)
                    .key(normalizedKey)
                    .build());
            return Optional.of(new StoredObjectMetadata(
                    normalizedKey,
                    result.contentType(),
                    Objects.requireNonNull(result.contentLength(), "OSS contentLength is missing"),
                    checksum(result.metadata())
            ));
        } catch (RuntimeException exception) {
            if (isNotFound(exception)) {
                return Optional.empty();
            }
            throw unavailable();
        }
    }

    @Override
    public void deleteControlledObject(String objectKey) {
        String normalizedKey = ObjectStoragePort.requireControlledObjectKey(objectKey);
        try {
            client.deleteObject(DeleteObjectRequest.newBuilder()
                    .bucket(bucket)
                    .key(normalizedKey)
                    .build());
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    @Override
    public SignedRead signRead(ReadSigningRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            PresignResult result = client.presign(
                    GetObjectRequest.newBuilder()
                            .bucket(bucket)
                            .key(request.objectKey())
                            .build(),
                    PresignOptions.newBuilder().expiration(request.expiresAt()).build()
            );
            return signedRead(result, request.expiresAt());
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private static SignedUpload signedUpload(PresignResult result, java.time.Instant requestedExpiration) {
        PresignResult value = Objects.requireNonNull(result, "OSS presign result is missing");
        requireMethod(value, "PUT");
        return new SignedUpload(
                URI.create(value.url()),
                value.signedHeaders().orElseGet(Map::of),
                value.expiration().orElse(requestedExpiration)
        );
    }

    private static SignedRead signedRead(PresignResult result, java.time.Instant requestedExpiration) {
        PresignResult value = Objects.requireNonNull(result, "OSS presign result is missing");
        requireMethod(value, "GET");
        return new SignedRead(
                URI.create(value.url()),
                value.expiration().orElse(requestedExpiration)
        );
    }

    private static void requireMethod(PresignResult result, String expected) {
        if (!expected.equalsIgnoreCase(result.method())) {
            throw new IllegalStateException("OSS returned an unexpected presigned HTTP method");
        }
    }

    private static String checksum(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        return metadata.entrySet().stream()
                .filter(entry -> CHECKSUM_METADATA_KEY.equals(entry.getKey().toLowerCase(Locale.ROOT)))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static boolean isNotFound(RuntimeException exception) {
        ServiceException serviceException = ServiceException.asCause(exception);
        return serviceException != null && serviceException.statusCode() == 404;
    }

    private static BusinessException unavailable() {
        return new BusinessException(AuctionErrorCode.STORAGE_UNAVAILABLE);
    }
}

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlibabaOssObjectStorageAdapterTest {

    private static final String BUCKET = "tidebid-dev";
    private static final String OBJECT_KEY =
            "dev/users/42/202609/550e8400-e29b-41d4-a716-446655440000.webp";
    private static final String SHA256 = "a".repeat(64);
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-12T01:10:00Z");

    private OSSClient client;
    private AlibabaOssObjectStorageAdapter storage;

    @BeforeEach
    void setUp() {
        client = mock(OSSClient.class);
        storage = new AlibabaOssObjectStorageAdapter(client, properties());
    }

    @Test
    void signsPutWithBoundMetadataAndReturnsOnlySdkRequiredHeaders() {
        when(client.presign(any(PutObjectRequest.class), any(PresignOptions.class)))
                .thenReturn(PresignResult.newBuilder()
                        .url("https://tidebid-dev.oss-cn-beijing.aliyuncs.com/object?signature=redacted")
                        .method("PUT")
                        .expiration(EXPIRES_AT)
                        .signedHeaders(Map.of(
                                "Content-Type", "image/webp",
                                "x-oss-forbid-overwrite", "true",
                                "x-oss-meta-tidebid-sha256", SHA256
                        ))
                        .build());

        ObjectStoragePort.SignedUpload signed = storage.signUpload(
                new ObjectStoragePort.UploadSigningRequest(
                        OBJECT_KEY, "image/webp", 4096, SHA256, EXPIRES_AT
                )
        );

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).presign(request.capture(), any(PresignOptions.class));
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo(OBJECT_KEY);
        assertThat(request.getValue().contentType()).isEqualTo("image/webp");
        assertThat(request.getValue().contentLength()).isEqualTo(4096);
        assertThat(request.getValue().forbidOverwrite()).isTrue();
        assertThat(request.getValue().metadata())
                .containsEntry(AlibabaOssObjectStorageAdapter.CHECKSUM_METADATA_KEY, SHA256);
        assertThat(signed.requiredHeaders())
                .containsEntry("x-oss-forbid-overwrite", "true")
                .containsEntry("x-oss-meta-tidebid-sha256", SHA256);
        assertThat(signed.toString()).doesNotContain("signature=redacted");
    }

    @Test
    void mapsHeadMetadataAndTreatsOnlyService404AsMissing() {
        HeadObjectResult result = mock(HeadObjectResult.class);
        when(result.contentType()).thenReturn("image/webp");
        when(result.contentLength()).thenReturn(4096L);
        when(result.metadata()).thenReturn(Map.of(
                AlibabaOssObjectStorageAdapter.CHECKSUM_METADATA_KEY, SHA256
        ));
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(result);

        assertThat(storage.headObject(OBJECT_KEY)).contains(
                new ObjectStoragePort.StoredObjectMetadata(OBJECT_KEY, "image/webp", 4096, SHA256)
        );

        ServiceException notFound = ServiceException.newBuilder()
                .statusCode(404)
                .errorFields(Map.of("Code", "NoSuchKey"))
                .build();
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(notFound);
        assertThat(storage.headObject(OBJECT_KEY)).isEmpty();

        ServiceException forbidden = ServiceException.newBuilder()
                .statusCode(403)
                .errorFields(Map.of("Code", "AccessDenied"))
                .build();
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(forbidden);
        assertUnavailable(() -> storage.headObject(OBJECT_KEY));
    }

    @Test
    void signsGetDeletesTheExpectedObjectAndSanitizesSdkFailures() {
        when(client.presign(any(GetObjectRequest.class), any(PresignOptions.class)))
                .thenReturn(PresignResult.newBuilder()
                        .url("https://tidebid-dev.oss-cn-beijing.aliyuncs.com/object?signature=redacted")
                        .method("GET")
                        .expiration(EXPIRES_AT)
                        .build());

        ObjectStoragePort.SignedRead signed = storage.signRead(
                new ObjectStoragePort.ReadSigningRequest(OBJECT_KEY, EXPIRES_AT)
        );
        assertThat(signed.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(signed.toString()).doesNotContain("signature=redacted");

        storage.deleteControlledObject(OBJECT_KEY);
        ArgumentCaptor<DeleteObjectRequest> deleteRequest = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(deleteRequest.capture());
        assertThat(deleteRequest.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(deleteRequest.getValue().key()).isEqualTo(OBJECT_KEY);

        when(client.presign(any(GetObjectRequest.class), any(PresignOptions.class)))
                .thenThrow(new IllegalStateException("provider detail containing a signed request"));
        assertUnavailable(() -> storage.signRead(
                new ObjectStoragePort.ReadSigningRequest(OBJECT_KEY, EXPIRES_AT)
        ));
    }

    private static AuctionStorageProperties properties() {
        return new AuctionStorageProperties(
                true,
                "https://oss-cn-beijing.aliyuncs.com",
                "cn-beijing",
                BUCKET,
                "test-access-key-id",
                "test-access-key-secret",
                "dev",
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                Duration.ofHours(24)
        );
    }

    private static void assertUnavailable(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.STORAGE_UNAVAILABLE);
                    assertThat(exception.getMessage()).doesNotContain("provider detail", "signed request");
                });
    }
}

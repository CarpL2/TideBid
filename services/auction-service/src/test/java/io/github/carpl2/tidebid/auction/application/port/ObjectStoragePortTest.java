package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.support.FakeObjectStorageAdapter;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectStoragePortTest {

    private static final String OBJECT_KEY =
            "dev/users/42/202609/550e8400-e29b-41d4-a716-446655440000.webp";
    private static final String SHA256 = "a".repeat(64);
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-12T01:10:00Z");

    @Test
    void fakeAdapterSupportsSigningMetadataLookupReadingAndDeletion() {
        FakeObjectStorageAdapter storage = new FakeObjectStorageAdapter();
        ObjectStoragePort.UploadSigningRequest uploadRequest = new ObjectStoragePort.UploadSigningRequest(
                OBJECT_KEY, "IMAGE/WEBP", 4096, SHA256.toUpperCase(), EXPIRES_AT
        );

        ObjectStoragePort.SignedUpload upload = storage.signUpload(uploadRequest);
        assertThat(upload.url()).hasScheme("https");
        assertThat(upload.requiredHeaders()).containsEntry("Content-Type", "image/webp");
        assertThat(upload.toString()).contains("url=[REDACTED]").doesNotContain(upload.url().toString());

        ObjectStoragePort.StoredObjectMetadata metadata = new ObjectStoragePort.StoredObjectMetadata(
                OBJECT_KEY, "image/webp", 4096, SHA256
        );
        storage.store(metadata);
        assertThat(storage.headObject(OBJECT_KEY)).contains(metadata);

        ObjectStoragePort.SignedRead read = storage.signRead(
                new ObjectStoragePort.ReadSigningRequest(OBJECT_KEY, EXPIRES_AT)
        );
        assertThat(read.url()).hasScheme("https");
        assertThat(read.toString()).contains("url=[REDACTED]").doesNotContain(read.url().toString());

        storage.deleteControlledObject(OBJECT_KEY);
        assertThat(storage.headObject(OBJECT_KEY)).isEmpty();
    }

    @Test
    void rejectsUnsafeKeysInvalidMetadataAndUnsafeSignedValues() {
        assertThatThrownBy(() -> ObjectStoragePort.requireControlledObjectKey("dev/users/42/../secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe path");
        assertThatThrownBy(() -> new ObjectStoragePort.UploadSigningRequest(
                OBJECT_KEY, "image/webp", 0, null, EXPIRES_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contentLength");
        assertThatThrownBy(() -> new ObjectStoragePort.StoredObjectMetadata(
                OBJECT_KEY, "image/webp", 4096, "not-a-sha256"
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64 hexadecimal");
        assertThatThrownBy(() -> new ObjectStoragePort.SignedUpload(
                URI.create("file:///tmp/object"), Map.of(), EXPIRES_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTP(S)");
        assertThatThrownBy(() -> new ObjectStoragePort.SignedUpload(
                URI.create("https://object-storage.invalid/object"), Map.of("X-Test", "ok\r\nevil"), EXPIRES_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line breaks");
    }
}

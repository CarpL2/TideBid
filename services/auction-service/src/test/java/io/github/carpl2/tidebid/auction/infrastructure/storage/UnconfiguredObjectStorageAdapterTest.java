package io.github.carpl2.tidebid.auction.infrastructure.storage;

import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnconfiguredObjectStorageAdapterTest {

    private static final String OBJECT_KEY =
            "dev/users/42/202609/550e8400-e29b-41d4-a716-446655440000.webp";
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-12T01:10:00Z");

    private final UnconfiguredObjectStorageAdapter storage = new UnconfiguredObjectStorageAdapter();

    @Test
    void everyStorageOperationFailsWithTheStableUnavailableError() {
        assertUnavailable(() -> storage.signUpload(new ObjectStoragePort.UploadSigningRequest(
                OBJECT_KEY, "image/webp", 4096, null, EXPIRES_AT
        )));
        assertUnavailable(() -> storage.headObject(OBJECT_KEY));
        assertUnavailable(() -> storage.deleteControlledObject(OBJECT_KEY));
        assertUnavailable(() -> storage.signRead(
                new ObjectStoragePort.ReadSigningRequest(OBJECT_KEY, EXPIRES_AT)
        ));
    }

    private static void assertUnavailable(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.STORAGE_UNAVAILABLE));
    }
}

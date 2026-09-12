package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionImageStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.support.FakeObjectStorageAdapter;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionImageVerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T03:00:00Z");
    private static final String OBJECT_KEY = "dev/users/42/202609/photo.webp";
    private static final String SHA256 = "a".repeat(64);

    private AuctionItemRepository repository;
    private FakeObjectStorageAdapter storage;
    private AuctionImageVerificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuctionItemRepository.class);
        storage = new FakeObjectStorageAdapter();
        service = new AuctionImageVerificationService(
                repository,
                storage,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void verifiesOwnedPendingUploadAgainstStoredObjectMetadata() {
        when(repository.findImageByObjectKey(OBJECT_KEY)).thenReturn(Optional.of(image(
                42L, AuctionImageStatus.PENDING, NOW.plusSeconds(60), SHA256
        )));
        storage.store(metadata("image/webp", 4096, SHA256));

        AuctionImageVerificationService.VerifiedUpload result =
                service.verifyPendingUpload(42L, OBJECT_KEY);

        assertThat(result.imageId()).isEqualTo(101L);
        assertThat(result.objectKey()).isEqualTo(OBJECT_KEY);
    }

    @Test
    void rejectsUnknownOrUnsafeObjectKeysWithoutCallingStorage() {
        when(repository.findImageByObjectKey(OBJECT_KEY)).thenReturn(Optional.empty());

        assertImageInvalid(() -> service.verifyPendingUpload(42L, OBJECT_KEY));
        assertImageInvalid(() -> service.verifyPendingUpload(42L, "../foreign.webp"));

        assertThat(storage.headRequests()).isEmpty();
    }

    @Test
    void rejectsAnotherOwnersUploadBeforeCallingStorage() {
        when(repository.findImageByObjectKey(OBJECT_KEY)).thenReturn(Optional.of(image(
                7L, AuctionImageStatus.PENDING, NOW.plusSeconds(60), null
        )));

        assertThatThrownBy(() -> service.verifyPendingUpload(42L, OBJECT_KEY))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.ASSET_ACCESS_DENIED));
        assertThat(storage.headRequests()).isEmpty();
    }

    @Test
    void rejectsExpiredOrNonPendingIntentsBeforeCallingStorage() {
        when(repository.findImageByObjectKey(OBJECT_KEY))
                .thenReturn(Optional.of(image(42L, AuctionImageStatus.PENDING, NOW, null)))
                .thenReturn(Optional.of(image(42L, AuctionImageStatus.EXPIRED, NOW.plusSeconds(60), null)));

        assertImageInvalid(() -> service.verifyPendingUpload(42L, OBJECT_KEY));
        assertImageInvalid(() -> service.verifyPendingUpload(42L, OBJECT_KEY));

        assertThat(storage.headRequests()).isEmpty();
    }

    @Test
    void rejectsMissingObjectAndEveryMismatchedMetadataField() {
        when(repository.findImageByObjectKey(OBJECT_KEY)).thenReturn(Optional.of(image(
                42L, AuctionImageStatus.PENDING, NOW.plusSeconds(60), SHA256
        )));

        assertImageInvalid(() -> service.verifyPendingUpload(42L, OBJECT_KEY));

        storage.store(metadata("image/png", 4096, SHA256));
        assertImageInvalid(() -> service.verifyPendingUpload(42L, OBJECT_KEY));

        storage.store(metadata("image/webp", 4097, SHA256));
        assertImageInvalid(() -> service.verifyPendingUpload(42L, OBJECT_KEY));

        storage.store(metadata("image/webp", 4096, "b".repeat(64)));
        assertImageInvalid(() -> service.verifyPendingUpload(42L, OBJECT_KEY));
    }

    @Test
    void permitsMissingObjectChecksumWhenIntentDidNotRequireOne() {
        when(repository.findImageByObjectKey(OBJECT_KEY)).thenReturn(Optional.of(image(
                42L, AuctionImageStatus.PENDING, NOW.plusSeconds(60), null
        )));
        storage.store(metadata("image/webp", 4096, null));

        assertThat(service.verifyPendingUpload(42L, OBJECT_KEY).imageId()).isEqualTo(101L);
        verify(repository).findImageByObjectKey(OBJECT_KEY);
    }

    @Test
    void verifiesBoundImageWithoutApplyingExpiredUploadIntentTime() {
        AuctionItemImage bound = boundImage(42L, 201L, 0, SHA256);
        storage.store(metadata("image/webp", 4096, SHA256));

        AuctionImageVerificationService.VerifiedUpload verified =
                service.verifyBoundImage(42L, 201L, bound);

        assertThat(verified.imageId()).isEqualTo(101L);
        assertThat(storage.headRequests()).containsExactly(OBJECT_KEY);
    }

    @Test
    void rejectsForeignUnboundOrMismatchedBoundImageBeforeSubmission() {
        AuctionItemImage bound = boundImage(7L, 201L, 0, SHA256);
        assertThatThrownBy(() -> service.verifyBoundImage(42L, 201L, bound))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.ASSET_ACCESS_DENIED));

        assertImageInvalid(() -> service.verifyBoundImage(
                42L, 201L, boundImage(42L, 202L, 0, SHA256)
        ));

        AuctionItemImage validBinding = boundImage(42L, 201L, 0, SHA256);
        assertImageInvalid(() -> service.verifyBoundImage(42L, 201L, validBinding));
        storage.store(metadata("image/png", 4096, SHA256));
        assertImageInvalid(() -> service.verifyBoundImage(42L, 201L, validBinding));
    }

    private static AuctionItemImage image(
            long ownerId,
            AuctionImageStatus status,
            Instant expiresAt,
            String checksum
    ) {
        return new AuctionItemImage(
                101L,
                null,
                ownerId,
                OBJECT_KEY,
                "photo.webp",
                "image/webp",
                4096,
                checksum,
                null,
                status,
                expiresAt,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60)
        );
    }

    private static AuctionItemImage boundImage(
            long ownerId,
            long itemId,
            int sortOrder,
            String checksum
    ) {
        return new AuctionItemImage(
                101L,
                itemId,
                ownerId,
                OBJECT_KEY,
                "photo.webp",
                "image/webp",
                4096,
                checksum,
                sortOrder,
                AuctionImageStatus.BOUND,
                NOW.minusSeconds(1),
                NOW.minusSeconds(120),
                NOW.minusSeconds(60)
        );
    }

    private static ObjectStoragePort.StoredObjectMetadata metadata(
            String contentType,
            long contentLength,
            String checksum
    ) {
        return new ObjectStoragePort.StoredObjectMetadata(
                OBJECT_KEY,
                contentType,
                contentLength,
                checksum
        );
    }

    private static void assertImageInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.IMAGE_INVALID));
    }
}

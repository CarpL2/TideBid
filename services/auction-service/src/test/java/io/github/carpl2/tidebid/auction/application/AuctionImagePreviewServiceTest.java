package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionImageStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionStorageProperties;
import io.github.carpl2.tidebid.auction.support.FakeObjectStorageAdapter;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuctionImagePreviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T03:00:00.123456789Z");
    private static final String OBJECT_KEY = "dev/users/42/202609/photo.webp";

    private AuctionItemRepository repository;
    private FakeObjectStorageAdapter storage;
    private AuctionImagePreviewService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuctionItemRepository.class);
        storage = new FakeObjectStorageAdapter();
        service = new AuctionImagePreviewService(
                repository,
                storage,
                storageProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void signsFiveMinutePreviewForOwnedPendingImage() {
        when(repository.findImageByObjectKey(OBJECT_KEY))
                .thenReturn(Optional.of(image(42L, AuctionImageStatus.PENDING)));

        AuctionImagePreviewService.ImagePreview preview = service.createOwnerPreview(42L, OBJECT_KEY);

        Instant expectedExpiry = Instant.parse("2026-09-12T03:05:00.123456Z");
        assertThat(preview.objectKey()).isEqualTo(OBJECT_KEY);
        assertThat(preview.url()).hasToString(
                "https://object-storage.invalid/" + OBJECT_KEY + "?operation=read"
        );
        assertThat(preview.expiresAt()).isEqualTo(expectedExpiry);
        assertThat(storage.readRequests()).containsExactly(
                new ObjectStoragePort.ReadSigningRequest(OBJECT_KEY, expectedExpiry)
        );
    }

    @Test
    void alsoPermitsOwnedBoundImage() {
        when(repository.findImageByObjectKey(OBJECT_KEY))
                .thenReturn(Optional.of(image(42L, AuctionImageStatus.BOUND)));

        assertThat(service.createOwnerPreview(42L, OBJECT_KEY).objectKey()).isEqualTo(OBJECT_KEY);
        assertThat(storage.readRequests()).hasSize(1);
    }

    @Test
    void rejectsUnknownAndUnsafeKeysBeforeSigning() {
        when(repository.findImageByObjectKey(OBJECT_KEY)).thenReturn(Optional.empty());

        assertImageInvalid(() -> service.createOwnerPreview(42L, OBJECT_KEY));
        assertImageInvalid(() -> service.createOwnerPreview(42L, "../foreign.webp"));

        assertThat(storage.readRequests()).isEmpty();
    }

    @Test
    void rejectsAnotherOwnersImageBeforeSigning() {
        when(repository.findImageByObjectKey(OBJECT_KEY))
                .thenReturn(Optional.of(image(7L, AuctionImageStatus.PENDING)));

        assertThatThrownBy(() -> service.createOwnerPreview(42L, OBJECT_KEY))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.ASSET_ACCESS_DENIED));
        assertThat(storage.readRequests()).isEmpty();
    }

    @Test
    void rejectsExpiredImageBeforeSigning() {
        when(repository.findImageByObjectKey(OBJECT_KEY))
                .thenReturn(Optional.of(image(42L, AuctionImageStatus.EXPIRED)));

        assertImageInvalid(() -> service.createOwnerPreview(42L, OBJECT_KEY));
        assertThat(storage.readRequests()).isEmpty();
    }

    @Test
    void redactsSignedUrlFromPreviewDiagnostics() {
        when(repository.findImageByObjectKey(OBJECT_KEY))
                .thenReturn(Optional.of(image(42L, AuctionImageStatus.PENDING)));

        AuctionImagePreviewService.ImagePreview preview = service.createOwnerPreview(42L, OBJECT_KEY);

        assertThat(preview.toString())
                .contains("url=[REDACTED]")
                .doesNotContain("operation=read");
    }

    private static AuctionItemImage image(long ownerId, AuctionImageStatus status) {
        Long itemId = status == AuctionImageStatus.BOUND ? 202L : null;
        Integer sortOrder = status == AuctionImageStatus.BOUND ? 0 : null;
        return new AuctionItemImage(
                101L, itemId, ownerId, OBJECT_KEY, "photo.webp", "image/webp", 4096,
                null, sortOrder, status, NOW.plusSeconds(600), NOW.minusSeconds(60), NOW.minusSeconds(60)
        );
    }

    private static AuctionStorageProperties storageProperties() {
        return new AuctionStorageProperties(
                false, "", "", "", "", "", "dev",
                Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofHours(24)
        );
    }

    private static void assertImageInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.IMAGE_INVALID));
    }
}

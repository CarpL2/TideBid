package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionImageStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionImageProperties;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionStorageProperties;
import io.github.carpl2.tidebid.auction.infrastructure.storage.UnconfiguredObjectStorageAdapter;
import io.github.carpl2.tidebid.auction.support.FakeObjectStorageAdapter;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionUploadIntentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T01:02:03.123456Z");
    private static final String SHA256 = "a".repeat(64);

    private AuctionItemRepository repository;
    private FakeObjectStorageAdapter storage;
    private AuctionUploadIntentService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuctionItemRepository.class);
        storage = new FakeObjectStorageAdapter();
        when(repository.insertImage(any(AuctionItemImage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = service(storage);
    }

    @Test
    void validatesGeneratesSignsAndPersistsAPendingUploadIntent() {
        AuctionUploadIntentService.UploadIntent result = service.create(
                new AuctionUploadIntentService.UploadIntentCommand(
                        42L,
                        "  键盘正面.WEBP  ",
                        "IMAGE/WEBP",
                        4096,
                        SHA256.toUpperCase()
                )
        );

        ArgumentCaptor<AuctionItemImage> image = ArgumentCaptor.forClass(AuctionItemImage.class);
        verify(repository).insertImage(image.capture());
        assertThat(image.getValue().ownerId()).isEqualTo(42L);
        assertThat(image.getValue().originalFilename()).isEqualTo("键盘正面.WEBP");
        assertThat(image.getValue().contentType()).isEqualTo("image/webp");
        assertThat(image.getValue().contentLength()).isEqualTo(4096L);
        assertThat(image.getValue().contentSha256()).isEqualTo(SHA256);
        assertThat(image.getValue().storageStatus()).isEqualTo(AuctionImageStatus.PENDING);
        assertThat(image.getValue().itemId()).isNull();
        assertThat(image.getValue().sortOrder()).isNull();
        assertThat(image.getValue().createdAt()).isEqualTo(NOW);
        assertThat(image.getValue().uploadExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(image.getValue().objectKey()).matches(
                "dev/users/42/202609/[a-f0-9-]{36}\\.webp"
        );

        assertThat(result.imageId()).isEqualTo(101L);
        assertThat(result.objectKey()).isEqualTo(image.getValue().objectKey());
        assertThat(result.upload().requiredHeaders())
                .containsEntry("Content-Type", "image/webp")
                .containsEntry("Content-Length", "4096");
        assertThat(result.toString()).contains("url=[REDACTED]").doesNotContain("object-storage.invalid");
    }

    @ParameterizedTest
    @MethodSource("invalidImages")
    void rejectsUnsafeOrMismatchedImageMetadata(
            String filename,
            String contentType,
            long contentLength,
            String checksum
    ) {
        assertInvalid(() -> service.create(new AuctionUploadIntentService.UploadIntentCommand(
                42L, filename, contentType, contentLength, checksum
        )));
        verify(repository, never()).insertImage(any());
    }

    @Test
    void doesNotPersistAnIntentWhenStorageSigningIsUnavailable() {
        AuctionUploadIntentService unavailableService = service(new UnconfiguredObjectStorageAdapter());

        assertThatThrownBy(() -> unavailableService.create(
                new AuctionUploadIntentService.UploadIntentCommand(
                        42L, "keyboard.webp", "image/webp", 4096, null
                )
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.STORAGE_UNAVAILABLE));
        verify(repository, never()).insertImage(any());
    }

    private AuctionUploadIntentService service(
            io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort objectStorage
    ) {
        return new AuctionUploadIntentService(
                repository,
                objectStorage,
                () -> 101L,
                new AuctionObjectKeyFactory(),
                imageProperties(),
                storageProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static Stream<Arguments> invalidImages() {
        return Stream.of(
                Arguments.of("../keyboard.webp", "image/webp", 4096L, null),
                Arguments.of("keyboard.png", "image/jpeg", 4096L, null),
                Arguments.of("keyboard.gif", "image/gif", 4096L, null),
                Arguments.of("keyboard.webp", "image/webp", 0L, null),
                Arguments.of("keyboard.webp", "image/webp", DataSize.ofMegabytes(10).toBytes() + 1, null),
                Arguments.of("keyboard.webp", "image/webp", 4096L, "invalid-sha256")
        );
    }

    private static void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.IMAGE_INVALID));
    }

    private static AuctionImageProperties imageProperties() {
        return new AuctionImageProperties(
                Set.of("image/jpeg", "image/png", "image/webp"),
                DataSize.ofMegabytes(10),
                9
        );
    }

    private static AuctionStorageProperties storageProperties() {
        return new AuctionStorageProperties(
                false,
                "",
                "",
                "",
                "",
                "",
                "dev",
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                Duration.ofHours(24)
        );
    }
}

package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionImageStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionImageCleanupProperties;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionStorageProperties;
import io.github.carpl2.tidebid.auction.support.FakeObjectStorageAdapter;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionPendingImageCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T03:00:00.123456789Z");
    private static final Instant TRUNCATED_NOW = Instant.parse("2026-09-12T03:00:00.123456Z");
    private static final Instant CREATED_BEFORE = TRUNCATED_NOW.minus(Duration.ofHours(24));
    private static final String GENERATED_KEY =
            "dev/users/42/202609/123e4567-e89b-12d3-a456-426614174000.webp";

    private AuctionItemRepository repository;
    private FakeObjectStorageAdapter storage;
    private AuctionPendingImageCleanupService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuctionItemRepository.class);
        storage = new FakeObjectStorageAdapter();
        service = service(repository, storage);
    }

    @Test
    void deletesGeneratedObjectThenAtomicallyExpiresPendingRecord() {
        AuctionItemImage candidate = pendingImage(101L, GENERATED_KEY);
        when(repository.findPendingImageCleanupCandidates(TRUNCATED_NOW, CREATED_BEFORE, 50))
                .thenReturn(List.of(candidate));
        when(repository.expirePendingImage(101L, TRUNCATED_NOW, CREATED_BEFORE, TRUNCATED_NOW))
                .thenReturn(true);

        AuctionPendingImageCleanupService.CleanupResult result = service.cleanupBatch();

        assertThat(result).isEqualTo(new AuctionPendingImageCleanupService.CleanupResult(1, 1, 1, 0, 0));
        assertThat(storage.deleteRequests()).containsExactly(GENERATED_KEY);
    }

    @Test
    void skipsObjectOutsideExactGeneratedNamespace() {
        AuctionItemImage foreign = pendingImage(102L, "dev/foreign/object.webp");
        when(repository.findPendingImageCleanupCandidates(TRUNCATED_NOW, CREATED_BEFORE, 50))
                .thenReturn(List.of(foreign));

        assertThat(service.cleanupBatch())
                .isEqualTo(new AuctionPendingImageCleanupService.CleanupResult(1, 0, 0, 1, 0));
        assertThat(storage.deleteRequests()).isEmpty();
        verify(repository, never()).expirePendingImage(anyLong(), any(), any(), any());
    }

    @Test
    void leavesRecordPendingWhenObjectDeletionFails() {
        ObjectStoragePort failingStorage = mock(ObjectStoragePort.class);
        AuctionPendingImageCleanupService failingService = service(repository, failingStorage);
        when(repository.findPendingImageCleanupCandidates(TRUNCATED_NOW, CREATED_BEFORE, 50))
                .thenReturn(List.of(pendingImage(103L, GENERATED_KEY)));
        doThrow(new BusinessException(AuctionErrorCode.STORAGE_UNAVAILABLE))
                .when(failingStorage).deleteControlledObject(GENERATED_KEY);

        assertThat(failingService.cleanupBatch())
                .isEqualTo(new AuctionPendingImageCleanupService.CleanupResult(1, 0, 0, 0, 1));
        verify(repository, never()).expirePendingImage(anyLong(), any(), any(), any());
    }

    @Test
    void toleratesAConcurrentWorkerWinningTheDatabaseCas() {
        when(repository.findPendingImageCleanupCandidates(TRUNCATED_NOW, CREATED_BEFORE, 50))
                .thenReturn(List.of(pendingImage(104L, GENERATED_KEY)));
        when(repository.expirePendingImage(104L, TRUNCATED_NOW, CREATED_BEFORE, TRUNCATED_NOW))
                .thenReturn(false);

        assertThat(service.cleanupBatch())
                .isEqualTo(new AuctionPendingImageCleanupService.CleanupResult(1, 1, 0, 0, 0));
        assertThat(storage.deleteRequests()).containsExactly(GENERATED_KEY);
    }

    @Test
    void doesNotHideDatabaseFailureAfterObjectWasDeleted() {
        when(repository.findPendingImageCleanupCandidates(TRUNCATED_NOW, CREATED_BEFORE, 50))
                .thenReturn(List.of(pendingImage(105L, GENERATED_KEY)));
        when(repository.expirePendingImage(105L, TRUNCATED_NOW, CREATED_BEFORE, TRUNCATED_NOW))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(service::cleanupBatch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        assertThat(storage.deleteRequests()).containsExactly(GENERATED_KEY);
    }

    private static AuctionPendingImageCleanupService service(
            AuctionItemRepository repository,
            ObjectStoragePort storage
    ) {
        return new AuctionPendingImageCleanupService(
                repository,
                storage,
                new AuctionStorageProperties(
                        false, "", "", "", "", "", "dev",
                        Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofHours(24)
                ),
                new AuctionImageCleanupProperties(Duration.ofMinutes(1), 50),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static AuctionItemImage pendingImage(long id, String objectKey) {
        Instant createdAt = CREATED_BEFORE.minusSeconds(60);
        return new AuctionItemImage(
                id, null, 42L, objectKey, "photo.webp", "image/webp", 4096,
                null, null, AuctionImageStatus.PENDING, createdAt.plusSeconds(600), createdAt, createdAt
        );
    }
}

package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.AuctionImageBindingService.BindImageCommand;
import io.github.carpl2.tidebid.auction.application.AuctionImageVerificationService.VerifiedUpload;
import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository.ImageBindingResult;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionImageStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionImageProperties;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionImageBindingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T04:00:00Z");
    private static final String OBJECT_KEY = "dev/users/42/202609/photo.webp";

    private AuctionItemRepository repository;
    private AuctionImageVerificationService verificationService;
    private AuctionImageBindingService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuctionItemRepository.class);
        verificationService = mock(AuctionImageVerificationService.class);
        service = new AuctionImageBindingService(
                repository,
                verificationService,
                new AuctionImageProperties(
                        Set.of("image/jpeg", "image/png", "image/webp"),
                        DataSize.ofMegabytes(10),
                        9
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void verifiesThenAtomicallyBindsAndReloadsImage() {
        BindImageCommand command = new BindImageCommand(42L, 201L, OBJECT_KEY, 0);
        when(repository.findItemById(201L)).thenReturn(Optional.of(item(
                42L, AuctionItemReviewStatus.DRAFT
        )));
        when(verificationService.verifyPendingUpload(42L, OBJECT_KEY))
                .thenReturn(new VerifiedUpload(101L, OBJECT_KEY));
        when(repository.bindPendingImage(101L, 42L, 201L, 0, NOW))
                .thenReturn(ImageBindingResult.BOUND);
        AuctionItemImage bound = boundImage();
        when(repository.findImageByObjectKey(OBJECT_KEY)).thenReturn(Optional.of(bound));

        assertThat(service.bind(command)).isEqualTo(bound);

        verify(verificationService).verifyPendingUpload(42L, OBJECT_KEY);
        verify(repository).bindPendingImage(101L, 42L, 201L, 0, NOW);
    }

    @Test
    void rejectsMissingForeignOrLockedItemsBeforeCallingObjectStorage() {
        BindImageCommand command = new BindImageCommand(42L, 201L, OBJECT_KEY, 0);
        when(repository.findItemById(201L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(item(7L, AuctionItemReviewStatus.DRAFT)))
                .thenReturn(Optional.of(item(42L, AuctionItemReviewStatus.PENDING_REVIEW)));

        assertError(AuctionErrorCode.ASSET_NOT_FOUND, () -> service.bind(command));
        assertError(AuctionErrorCode.ASSET_ACCESS_DENIED, () -> service.bind(command));
        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.bind(command));

        verify(verificationService, never()).verifyPendingUpload(42L, OBJECT_KEY);
    }

    @Test
    void rejectsInvalidPositionBeforeLoadingItem() {
        assertError(
                AuctionErrorCode.IMAGE_INVALID,
                () -> service.bind(new BindImageCommand(42L, 201L, OBJECT_KEY, 9))
        );
        verify(repository, never()).findItemById(201L);
    }

    @Test
    void mapsConcurrentRebindAndOccupiedPositionToImageErrors() {
        BindImageCommand command = new BindImageCommand(42L, 201L, OBJECT_KEY, 0);
        when(repository.findItemById(201L)).thenReturn(Optional.of(item(
                42L, AuctionItemReviewStatus.REJECTED
        )));
        when(verificationService.verifyPendingUpload(42L, OBJECT_KEY))
                .thenReturn(new VerifiedUpload(101L, OBJECT_KEY));
        when(repository.bindPendingImage(101L, 42L, 201L, 0, NOW))
                .thenReturn(ImageBindingResult.NOT_PENDING)
                .thenReturn(ImageBindingResult.POSITION_OCCUPIED);

        assertError(AuctionErrorCode.IMAGE_INVALID, () -> service.bind(command));
        assertError(AuctionErrorCode.IMAGE_INVALID, () -> service.bind(command));
    }

    private static AuctionItem item(long sellerId, AuctionItemReviewStatus status) {
        int submissionVersion = status == AuctionItemReviewStatus.DRAFT ? 0 : 1;
        Instant submittedAt = status == AuctionItemReviewStatus.DRAFT ? null : NOW.minusSeconds(30);
        return new AuctionItem(
                201L,
                sellerId,
                "Mechanical keyboard",
                "A keyboard used to verify image binding",
                "ELECTRONICS",
                AuctionItemCondition.GOOD,
                status,
                submissionVersion,
                0,
                submittedAt,
                null,
                NOW.minusSeconds(60),
                NOW.minusSeconds(30)
        );
    }

    private static AuctionItemImage boundImage() {
        return new AuctionItemImage(
                101L,
                201L,
                42L,
                OBJECT_KEY,
                "photo.webp",
                "image/webp",
                4096,
                null,
                0,
                AuctionImageStatus.BOUND,
                NOW.plusSeconds(60),
                NOW.minusSeconds(60),
                NOW
        );
    }

    private static void assertError(AuctionErrorCode errorCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(errorCode));
    }
}

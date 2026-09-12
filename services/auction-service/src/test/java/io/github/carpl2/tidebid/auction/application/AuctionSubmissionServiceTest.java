package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSubmissionTransaction;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionImageStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionTimingProperties;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuctionSubmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00.123456789Z");
    private static final long SELLER_ID = 42L;
    private static final long ITEM_ID = 101L;

    private AuctionItemRepository itemRepository;
    private AuctionSessionRepository sessionRepository;
    private AuctionImageVerificationService imageVerificationService;
    private AuctionSubmissionTransaction transaction;
    private AuctionSubmissionService service;

    @BeforeEach
    void setUp() {
        itemRepository = mock(AuctionItemRepository.class);
        sessionRepository = mock(AuctionSessionRepository.class);
        imageVerificationService = mock(AuctionImageVerificationService.class);
        transaction = mock(AuctionSubmissionTransaction.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new AuctionSubmissionService(
                itemRepository,
                sessionRepository,
                imageVerificationService,
                new AuctionDraftFieldsValidator(
                        new AuctionTimingProperties(Duration.ofMinutes(1), Duration.ofDays(7), Duration.ofSeconds(1)),
                        clock
                ),
                transaction,
                clock
        );
    }

    @Test
    void verifiesEveryBoundImageThenSubmitsTheExactDraftVersion() {
        AuctionItem item = item(AuctionItemReviewStatus.DRAFT, 0, 3L);
        AuctionSession session = session(AuctionSessionStatus.DRAFT, 5L, NOW.plusSeconds(120));
        List<AuctionItemImage> images = List.of(image(301L, 0), image(302L, 1));
        AuctionSubmissionTransaction.SubmittedAuction submitted = new AuctionSubmissionTransaction.SubmittedAuction(
                item(AuctionItemReviewStatus.PENDING_REVIEW, 1, 4L), session
        );
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(item));
        when(sessionRepository.findSessionByItemId(ITEM_ID)).thenReturn(Optional.of(session));
        when(itemRepository.findBoundImagesByItemIds(List.of(ITEM_ID))).thenReturn(images);
        when(transaction.submit(ITEM_ID, SELLER_ID, 3L, 5L, NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS)))
                .thenReturn(submitted);

        assertThat(service.submit(command(3L, 5L))).isSameAs(submitted);

        verify(imageVerificationService).verifyBoundImage(SELLER_ID, ITEM_ID, images.get(0));
        verify(imageVerificationService).verifyBoundImage(SELLER_ID, ITEM_ID, images.get(1));
        verify(transaction).submit(
                ITEM_ID, SELLER_ID, 3L, 5L, NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        );
    }

    @Test
    void permitsRejectedItemToBeResubmitted() {
        AuctionItem item = item(AuctionItemReviewStatus.REJECTED, 1, 7L);
        AuctionSession session = session(AuctionSessionStatus.DRAFT, 2L, NOW.plusSeconds(120));
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(item));
        when(sessionRepository.findSessionByItemId(ITEM_ID)).thenReturn(Optional.of(session));
        when(itemRepository.findBoundImagesByItemIds(List.of(ITEM_ID))).thenReturn(List.of(image(301L, 0)));
        when(transaction.submit(anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(
                new AuctionSubmissionTransaction.SubmittedAuction(
                        item(AuctionItemReviewStatus.PENDING_REVIEW, 2, 8L), session
                )
        );

        assertThat(service.submit(command(7L, 2L)).item().submissionVersion()).isEqualTo(2);
    }

    @Test
    void rejectsMissingForeignLockedOrStaleDraftBeforeCheckingImages() {
        when(itemRepository.findItemById(ITEM_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(item(99L, AuctionItemReviewStatus.DRAFT, 0, 0L)))
                .thenReturn(Optional.of(item(AuctionItemReviewStatus.PENDING_REVIEW, 1, 1L)))
                .thenReturn(Optional.of(item(AuctionItemReviewStatus.DRAFT, 0, 2L)));
        when(sessionRepository.findSessionByItemId(ITEM_ID)).thenReturn(Optional.of(
                session(AuctionSessionStatus.DRAFT, 0L, NOW.plusSeconds(120))
        ));

        assertError(AuctionErrorCode.ASSET_NOT_FOUND, () -> service.submit(command(0L, 0L)));
        assertError(AuctionErrorCode.ASSET_ACCESS_DENIED, () -> service.submit(command(0L, 0L)));
        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.submit(command(1L, 0L)));
        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.submit(command(0L, 0L)));

        verify(itemRepository, never()).findBoundImagesByItemIds(any());
        verifyNoInteractions(imageVerificationService, transaction);
    }

    @Test
    void rejectsNonDraftOrStaleSessionBeforeCheckingImages() {
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(
                item(AuctionItemReviewStatus.DRAFT, 0, 0L)
        ));
        when(sessionRepository.findSessionByItemId(ITEM_ID))
                .thenReturn(Optional.of(session(AuctionSessionStatus.OPEN, 0L, NOW.plusSeconds(120))))
                .thenReturn(Optional.of(session(AuctionSessionStatus.DRAFT, 2L, NOW.plusSeconds(120))));

        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.submit(command(0L, 0L)));
        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.submit(command(0L, 0L)));

        verify(itemRepository, never()).findBoundImagesByItemIds(any());
    }

    @Test
    void rejectsMissingOrNonContiguousImagesWithoutCallingStorage() {
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(
                item(AuctionItemReviewStatus.DRAFT, 0, 0L)
        ));
        when(sessionRepository.findSessionByItemId(ITEM_ID)).thenReturn(Optional.of(
                session(AuctionSessionStatus.DRAFT, 0L, NOW.plusSeconds(120))
        ));
        when(itemRepository.findBoundImagesByItemIds(List.of(ITEM_ID)))
                .thenReturn(List.of())
                .thenReturn(List.of(image(301L, 1)));

        assertError(AuctionErrorCode.IMAGE_INVALID, () -> service.submit(command(0L, 0L)));
        assertError(AuctionErrorCode.IMAGE_INVALID, () -> service.submit(command(0L, 0L)));

        verifyNoInteractions(imageVerificationService, transaction);
    }

    @Test
    void abortsBeforeTransactionWhenHeadVerificationOrTimingFails() {
        AuctionItemImage image = image(301L, 0);
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(
                item(AuctionItemReviewStatus.DRAFT, 0, 0L)
        ));
        when(sessionRepository.findSessionByItemId(ITEM_ID))
                .thenReturn(Optional.of(session(AuctionSessionStatus.DRAFT, 0L, NOW.plusSeconds(120))))
                .thenReturn(Optional.of(session(AuctionSessionStatus.DRAFT, 0L, NOW.plusSeconds(30))));
        when(itemRepository.findBoundImagesByItemIds(List.of(ITEM_ID))).thenReturn(List.of(image));
        doThrow(new BusinessException(AuctionErrorCode.IMAGE_INVALID))
                .doReturn(new AuctionImageVerificationService.VerifiedUpload(image.id(), image.objectKey()))
                .when(imageVerificationService).verifyBoundImage(SELLER_ID, ITEM_ID, image);

        assertError(AuctionErrorCode.IMAGE_INVALID, () -> service.submit(command(0L, 0L)));
        assertError(AuctionErrorCode.AUCTION_TIME_INVALID, () -> service.submit(command(0L, 0L)));

        verify(transaction, never()).submit(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void mapsConcurrentCasFailureToStateConflict() {
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(
                item(AuctionItemReviewStatus.DRAFT, 0, 0L)
        ));
        when(sessionRepository.findSessionByItemId(ITEM_ID)).thenReturn(Optional.of(
                session(AuctionSessionStatus.DRAFT, 0L, NOW.plusSeconds(120))
        ));
        when(itemRepository.findBoundImagesByItemIds(List.of(ITEM_ID))).thenReturn(List.of(image(301L, 0)));
        when(transaction.submit(anyLong(), anyLong(), anyLong(), anyLong(), any())).thenThrow(
                new AuctionSubmissionTransaction.SubmissionConflictException("changed concurrently")
        );

        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.submit(command(0L, 0L)));
    }

    private static AuctionSubmissionService.SubmitCommand command(long itemVersion, long sessionVersion) {
        return new AuctionSubmissionService.SubmitCommand(
                SELLER_ID, ITEM_ID, itemVersion, sessionVersion
        );
    }

    private static AuctionItem item(AuctionItemReviewStatus status, int submissionVersion, long version) {
        return item(SELLER_ID, status, submissionVersion, version);
    }

    private static AuctionItem item(
            long sellerId,
            AuctionItemReviewStatus status,
            int submissionVersion,
            long version
    ) {
        Instant submittedAt = status == AuctionItemReviewStatus.DRAFT ? null : NOW.minusSeconds(600);
        return new AuctionItem(
                ITEM_ID, sellerId, "Mechanical keyboard", "A complete auction item description",
                "ELECTRONICS", AuctionItemCondition.GOOD, status, submissionVersion, version,
                submittedAt, null, NOW.minusSeconds(3600), NOW.minusSeconds(300)
        );
    }

    private static AuctionSession session(AuctionSessionStatus status, long version, Instant startAt) {
        return new AuctionSession(
                201L, ITEM_ID, SELLER_ID,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0, startAt, startAt.plusSeconds(3600), status, version,
                NOW.minusSeconds(3600), NOW.minusSeconds(300)
        );
    }

    private static AuctionItemImage image(long imageId, int sortOrder) {
        return new AuctionItemImage(
                imageId, ITEM_ID, SELLER_ID, "dev/users/42/202609/" + imageId + ".webp",
                "image.webp", "image/webp", 4096, null, sortOrder, AuctionImageStatus.BOUND,
                NOW.plusSeconds(60), NOW.minusSeconds(3600), NOW.minusSeconds(300)
        );
    }

    private static void assertError(AuctionErrorCode errorCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(errorCode));
    }
}

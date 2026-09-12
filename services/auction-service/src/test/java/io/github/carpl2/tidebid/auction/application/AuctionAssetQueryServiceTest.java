package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionImageStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionReview;
import io.github.carpl2.tidebid.auction.domain.AuctionReviewDecision;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionStorageProperties;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.net.URI;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuctionAssetQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private static final long SELLER_ID = 42L;

    private AuctionItemRepository itemRepository;
    private AuctionSessionRepository sessionRepository;
    private ObjectStoragePort objectStorage;

    @BeforeEach
    void setUp() {
        itemRepository = mock(AuctionItemRepository.class);
        sessionRepository = mock(AuctionSessionRepository.class);
        objectStorage = mock(ObjectStoragePort.class);
    }

    @Test
    void returnsStableSellerPageUsingBatchSessionAndImageReads() {
        AuctionItem newest = item(102L, SELLER_ID, AuctionItemReviewStatus.DRAFT);
        AuctionItem older = item(101L, SELLER_ID, AuctionItemReviewStatus.REJECTED);
        when(itemRepository.findItemsBySeller(SELLER_ID, 0, 2))
                .thenReturn(new AuctionItemRepository.SellerItemPage(List.of(newest, older), 3L));
        when(sessionRepository.findSessionsByItemIds(List.of(102L, 101L)))
                .thenReturn(List.of(session(201L, 101L), session(202L, 102L)));
        when(itemRepository.findBoundImagesByItemIds(List.of(102L, 101L)))
                .thenReturn(List.of(image(302L, 101L, 0), image(301L, 102L, 0), image(303L, 102L, 1)));
        when(objectStorage.signRead(any())).thenAnswer(invocation -> {
            ObjectStoragePort.ReadSigningRequest request = invocation.getArgument(0);
            return new ObjectStoragePort.SignedRead(
                    URI.create("https://object-storage.invalid/read/" + request.objectKey()), request.expiresAt()
            );
        });
        AuctionAssetQueryService service = service(true);

        AuctionAssetQueryService.PageResult<AuctionAssetQueryService.AssetSummary> result =
                service.findMine(SELLER_ID, 1, 2);

        assertThat(result.total()).isEqualTo(3L);
        assertThat(result.totalPages()).isEqualTo(2L);
        assertThat(result.items()).extracting(AuctionAssetQueryService.AssetSummary::itemId)
                .containsExactly(102L, 101L);
        assertThat(result.items().getFirst().auctionId()).isEqualTo(202L);
        assertThat(result.items().getFirst().coverImage().imageId()).isEqualTo(301L);
        assertThat(result.items().getFirst().coverImage().previewUrl()).isNotNull();

        ArgumentCaptor<List<Long>> sessionIds = listCaptor();
        ArgumentCaptor<List<Long>> imageIds = listCaptor();
        verify(sessionRepository).findSessionsByItemIds(sessionIds.capture());
        verify(itemRepository).findBoundImagesByItemIds(imageIds.capture());
        assertThat(sessionIds.getValue()).containsExactly(102L, 101L);
        assertThat(imageIds.getValue()).containsExactly(102L, 101L);
    }

    @Test
    void emptyPageDoesNotIssueBatchQueriesOrStorageCalls() {
        when(itemRepository.findItemsBySeller(SELLER_ID, 20, 20))
                .thenReturn(new AuctionItemRepository.SellerItemPage(List.of(), 1L));
        AuctionAssetQueryService service = service(true);

        AuctionAssetQueryService.PageResult<AuctionAssetQueryService.AssetSummary> result =
                service.findMine(SELLER_ID, 2, 20);

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isOne();
        verify(sessionRepository, never()).findSessionsByItemIds(any());
        verify(itemRepository, never()).findBoundImagesByItemIds(any());
        verifyNoInteractions(objectStorage);
    }

    @Test
    void ownerCanReadDraftWithOrderedImagesAndLatestReviewFeedback() {
        AuctionItem rejected = item(101L, SELLER_ID, AuctionItemReviewStatus.REJECTED);
        when(itemRepository.findItemById(101L)).thenReturn(Optional.of(rejected));
        when(sessionRepository.findSessionByItemId(101L)).thenReturn(Optional.of(session(201L, 101L)));
        when(itemRepository.findBoundImagesByItemIds(List.of(101L)))
                .thenReturn(List.of(image(301L, 101L, 0), image(302L, 101L, 1)));
        when(itemRepository.findLatestReview(101L)).thenReturn(Optional.of(new AuctionReview(
                401L, 101L, 1, 99L, AuctionReviewDecision.REJECTED,
                "Please add clearer photos", NOW.minusSeconds(600)
        )));
        when(objectStorage.signRead(any())).thenAnswer(invocation -> {
            ObjectStoragePort.ReadSigningRequest request = invocation.getArgument(0);
            return new ObjectStoragePort.SignedRead(
                    URI.create("https://object-storage.invalid/read?image=" + request.objectKey()),
                    request.expiresAt()
            );
        });

        AuctionAssetQueryService.AssetDetail detail = service(true).findDetail(SELLER_ID, false, 101L);

        assertThat(detail.itemId()).isEqualTo(101L);
        assertThat(detail.images()).extracting(AuctionAssetQueryService.ImageView::sortOrder)
                .containsExactly(0, 1);
        assertThat(detail.images()).extracting(AuctionAssetQueryService.ImageView::previewExpiresAt)
                .containsOnly(NOW.plus(Duration.ofMinutes(5)));
        assertThat(detail.latestReview().decision()).isEqualTo(AuctionReviewDecision.REJECTED);
        assertThat(detail.latestReview().comment()).isEqualTo("Please add clearer photos");
    }

    @Test
    void accessPolicyAllowsPendingAdministratorAndApprovedAuthenticatedUser() {
        AuctionItem pending = item(101L, SELLER_ID, AuctionItemReviewStatus.PENDING_REVIEW);
        AuctionItem approved = item(102L, SELLER_ID, AuctionItemReviewStatus.APPROVED);
        when(itemRepository.findItemById(101L)).thenReturn(Optional.of(pending));
        when(itemRepository.findItemById(102L)).thenReturn(Optional.of(approved));
        when(sessionRepository.findSessionByItemId(101L)).thenReturn(Optional.of(session(201L, 101L)));
        when(sessionRepository.findSessionByItemId(102L)).thenReturn(Optional.of(session(202L, 102L)));
        when(itemRepository.findBoundImagesByItemIds(any())).thenReturn(List.of());
        when(itemRepository.findLatestReview(anyLong())).thenReturn(Optional.empty());
        AuctionAssetQueryService service = service(false);

        assertThat(service.findDetail(99L, true, 101L).reviewStatus())
                .isEqualTo(AuctionItemReviewStatus.PENDING_REVIEW);
        AuctionAssetQueryService.AssetDetail approvedDetail = service.findDetail(88L, false, 102L);
        assertThat(approvedDetail.reviewStatus()).isEqualTo(AuctionItemReviewStatus.APPROVED);
        assertThat(approvedDetail.latestReview()).isNull();
    }

    @Test
    void rejectsUnauthorizedDraftBeforeReadingRelatedData() {
        when(itemRepository.findItemById(101L)).thenReturn(Optional.of(
                item(101L, SELLER_ID, AuctionItemReviewStatus.DRAFT)
        ));

        assertError(AuctionErrorCode.ASSET_ACCESS_DENIED,
                () -> service(false).findDetail(99L, false, 101L));

        verify(sessionRepository, never()).findSessionByItemId(anyLong());
        verify(itemRepository, never()).findBoundImagesByItemIds(any());
    }

    @Test
    void disabledStorageKeepsCoreReadsAvailableWithoutPreviewUrls() {
        AuctionItem item = item(101L, SELLER_ID, AuctionItemReviewStatus.DRAFT);
        when(itemRepository.findItemById(101L)).thenReturn(Optional.of(item));
        when(sessionRepository.findSessionByItemId(101L)).thenReturn(Optional.of(session(201L, 101L)));
        when(itemRepository.findBoundImagesByItemIds(List.of(101L))).thenReturn(List.of(image(301L, 101L, 0)));
        when(itemRepository.findLatestReview(101L)).thenReturn(Optional.empty());

        AuctionAssetQueryService.AssetDetail detail = service(false).findDetail(SELLER_ID, false, 101L);

        assertThat(detail.images().getFirst().previewUrl()).isNull();
        assertThat(detail.images().getFirst().previewExpiresAt()).isNull();
        verifyNoInteractions(objectStorage);
    }

    @Test
    void rejectsMissingAssetAndInvalidPagination() {
        when(itemRepository.findItemById(999L)).thenReturn(Optional.empty());
        AuctionAssetQueryService service = service(false);

        assertError(AuctionErrorCode.ASSET_NOT_FOUND, () -> service.findDetail(SELLER_ID, false, 999L));
        assertError(AuctionErrorCode.ASSET_INVALID, () -> service.findMine(SELLER_ID, 0, 20));
        assertError(AuctionErrorCode.ASSET_INVALID, () -> service.findMine(SELLER_ID, 1, 101));
        assertError(AuctionErrorCode.ASSET_INVALID,
                () -> service.findMine(SELLER_ID, Integer.MAX_VALUE, 100));
    }

    private AuctionAssetQueryService service(boolean storageEnabled) {
        return new AuctionAssetQueryService(
                itemRepository,
                sessionRepository,
                objectStorage,
                storageProperties(storageEnabled),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static AuctionStorageProperties storageProperties(boolean enabled) {
        return new AuctionStorageProperties(
                enabled,
                enabled ? "https://oss-cn-beijing.aliyuncs.com" : "",
                enabled ? "cn-beijing" : "",
                enabled ? "tidebid-dev" : "",
                enabled ? "test-access-key" : "",
                enabled ? "test-access-secret" : "",
                "dev",
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                Duration.ofHours(24)
        );
    }

    private static AuctionItem item(long itemId, long sellerId, AuctionItemReviewStatus status) {
        boolean draft = status == AuctionItemReviewStatus.DRAFT;
        boolean approved = status == AuctionItemReviewStatus.APPROVED;
        return new AuctionItem(
                itemId, sellerId, "Mechanical keyboard", "A sufficiently detailed item description",
                "ELECTRONICS", AuctionItemCondition.GOOD, status,
                draft ? 0 : 1, 2L,
                draft ? null : NOW.minusSeconds(1200),
                approved ? NOW.minusSeconds(600) : null,
                NOW.minusSeconds(3600), NOW.minusSeconds(300)
        );
    }

    private static AuctionSession session(long auctionId, long itemId) {
        return new AuctionSession(
                auctionId, itemId, SELLER_ID,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200),
                AuctionSessionStatus.DRAFT, 3L, NOW.minusSeconds(3600), NOW.minusSeconds(300)
        );
    }

    private static AuctionItemImage image(long imageId, long itemId, int sortOrder) {
        return new AuctionItemImage(
                imageId, itemId, SELLER_ID,
                "dev/users/42/202609/image-" + imageId + ".webp",
                "image-" + imageId + ".webp", "image/webp", 4096L, null, sortOrder,
                AuctionImageStatus.BOUND, NOW.plusSeconds(600), NOW.minusSeconds(3600), NOW.minusSeconds(300)
        );
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Long>> listCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static void assertError(AuctionErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }
}

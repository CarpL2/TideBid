package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionImageStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionStorageProperties;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuctionLobbyQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T04:00:00Z");
    private static final long SELLER_ID = 42L;

    private AuctionItemRepository itemRepository;
    private AuctionSessionRepository sessionRepository;
    private ObjectStoragePort objectStorage;
    private AuctionSessionLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        itemRepository = mock(AuctionItemRepository.class);
        sessionRepository = mock(AuctionSessionRepository.class);
        objectStorage = mock(ObjectStoragePort.class);
        lifecycleService = mock(AuctionSessionLifecycleService.class);
    }

    @Test
    void returnsLifecycleCorrectedPageUsingBatchReadsAndOnlySignsCovers() {
        AuctionSession first = session(201L, 101L, AuctionSessionStatus.SCHEDULED, null, 0L);
        AuctionSession second = session(202L, 102L, AuctionSessionStatus.OPEN, new BigDecimal("130.00"), 3L);
        AuctionSession opened = session(201L, 101L, AuctionSessionStatus.OPEN, null, 0L);
        when(sessionRepository.findLobbySessions(0, 2))
                .thenReturn(new AuctionSessionRepository.LobbySessionPage(List.of(first, second), 3L));
        when(lifecycleService.advanceToCurrentState(first)).thenReturn(opened);
        when(lifecycleService.advanceToCurrentState(second)).thenReturn(second);
        when(itemRepository.findItemsByIds(List.of(101L, 102L)))
                .thenReturn(List.of(item(102L), item(101L)));
        when(itemRepository.findBoundImagesByItemIds(List.of(101L, 102L)))
                .thenReturn(List.of(image(301L, 101L, 0), image(302L, 101L, 1), image(303L, 102L, 0)));
        when(objectStorage.signRead(any())).thenAnswer(invocation -> {
            ObjectStoragePort.ReadSigningRequest request = invocation.getArgument(0);
            return new ObjectStoragePort.SignedRead(
                    URI.create("https://object-storage.invalid/read/" + request.objectKey()), request.expiresAt()
            );
        });

        AuctionAssetQueryService.PageResult<AuctionAssetQueryService.LobbySummary> result =
                service(true).findLobby(1, 2);

        assertThat(result.total()).isEqualTo(3L);
        assertThat(result.totalPages()).isEqualTo(2L);
        assertThat(result.items()).extracting(AuctionAssetQueryService.LobbySummary::auctionId)
                .containsExactly(201L, 202L);
        assertThat(result.items()).extracting(AuctionAssetQueryService.LobbySummary::sessionStatus)
                .containsExactly(AuctionSessionStatus.OPEN, AuctionSessionStatus.OPEN);
        assertThat(result.items().getFirst().displayPrice()).isEqualByComparingTo("100.00");
        assertThat(result.items().get(1).displayPrice()).isEqualByComparingTo("130.00");
        assertThat(result.items().getFirst().minimumNextBid()).isEqualByComparingTo("100.00");
        assertThat(result.items().get(1).minimumNextBid()).isEqualByComparingTo("140.00");
        assertThat(result.items()).extracting(summary -> summary.coverImage().imageId())
                .containsExactly(301L, 303L);
        verify(objectStorage, times(2)).signRead(any());
    }

    @Test
    void emptyPageAvoidsLifecycleItemImageAndStorageReads() {
        when(sessionRepository.findLobbySessions(20, 20))
                .thenReturn(new AuctionSessionRepository.LobbySessionPage(List.of(), 1L));

        AuctionAssetQueryService.PageResult<AuctionAssetQueryService.LobbySummary> result =
                service(true).findLobby(2, 20);

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isOne();
        verifyNoInteractions(itemRepository, objectStorage, lifecycleService);
    }

    @Test
    void rejectsInvalidPaginationBeforeReadingRepositories() {
        AuctionAssetQueryService service = service(false);

        assertThatThrownBy(() -> service.findLobby(0, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.findLobby(1, 101)).isInstanceOf(BusinessException.class);

        verify(sessionRepository, never()).findLobbySessions(any(Integer.class), any(Integer.class));
    }

    @Test
    void rejectsLobbySessionBackedByUnapprovedItem() {
        AuctionSession session = session(201L, 101L, AuctionSessionStatus.OPEN, null, 0L);
        when(sessionRepository.findLobbySessions(0, 20))
                .thenReturn(new AuctionSessionRepository.LobbySessionPage(List.of(session), 1L));
        when(lifecycleService.advanceToCurrentState(session)).thenReturn(session);
        AuctionItem approved = item(101L);
        when(itemRepository.findItemsByIds(List.of(101L))).thenReturn(List.of(new AuctionItem(
                approved.id(), approved.sellerId(), approved.title(), approved.description(), approved.category(),
                approved.itemCondition(), AuctionItemReviewStatus.PENDING_REVIEW, 1, approved.version(),
                approved.submittedAt(), null, approved.createdAt(), approved.updatedAt()
        )));
        when(itemRepository.findBoundImagesByItemIds(List.of(101L))).thenReturn(List.of());

        assertThatThrownBy(() -> service(false).findLobby(1, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inconsistent approved item");
    }

    private AuctionAssetQueryService service(boolean storageEnabled) {
        return new AuctionAssetQueryService(
                itemRepository, sessionRepository, objectStorage, storageProperties(storageEnabled),
                lifecycleService, Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static AuctionStorageProperties storageProperties(boolean enabled) {
        return new AuctionStorageProperties(
                enabled, enabled ? "https://oss-cn-beijing.aliyuncs.com" : "",
                enabled ? "cn-beijing" : "", enabled ? "tidebid-dev" : "",
                enabled ? "test-access-key" : "", enabled ? "test-access-secret" : "", "dev",
                Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofHours(24)
        );
    }

    private static AuctionItem item(long itemId) {
        return new AuctionItem(
                itemId, SELLER_ID, "Mechanical keyboard", "A sufficiently detailed item description",
                "ELECTRONICS", AuctionItemCondition.GOOD, AuctionItemReviewStatus.APPROVED, 1, 2L,
                NOW.minusSeconds(1800), NOW.minusSeconds(1200),
                NOW.minusSeconds(3600), NOW.minusSeconds(600)
        );
    }

    private static AuctionSession session(
            long auctionId,
            long itemId,
            AuctionSessionStatus status,
            BigDecimal currentPrice,
            long bidCount
    ) {
        return new AuctionSession(
                auctionId, itemId, SELLER_ID,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                currentPrice, currentPrice == null ? null : 88L, bidCount,
                NOW.minusSeconds(60), NOW.plusSeconds(3600), status, 3L,
                NOW.minusSeconds(3600), NOW.minusSeconds(30)
        );
    }

    private static AuctionItemImage image(long imageId, long itemId, int sortOrder) {
        return new AuctionItemImage(
                imageId, itemId, SELLER_ID, "dev/users/42/image-" + imageId + ".webp",
                "image.webp", "image/webp", 4096L, null, sortOrder, AuctionImageStatus.BOUND,
                NOW.plusSeconds(600), NOW.minusSeconds(3600), NOW.minusSeconds(300)
        );
    }
}

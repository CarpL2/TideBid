package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionImageStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuctionDetailQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T05:00:00Z");
    private static final long SELLER_ID = 42L;
    private static final long BUYER_ID = 88L;
    private static final long ITEM_ID = 101L;
    private static final long AUCTION_ID = 201L;

    private AuctionSessionRepository sessionRepository;
    private AuctionItemRepository itemRepository;
    private AuctionRegistrationRepository registrationRepository;
    private AuctionSessionLifecycleService lifecycleService;
    private ObjectStoragePort objectStorage;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AuctionSessionRepository.class);
        itemRepository = mock(AuctionItemRepository.class);
        registrationRepository = mock(AuctionRegistrationRepository.class);
        lifecycleService = mock(AuctionSessionLifecycleService.class);
        objectStorage = mock(ObjectStoragePort.class);
    }

    @Test
    void returnsCorrectedAuctionAllImagesMinimumBidAndCurrentUsersRegistration() {
        AuctionSession scheduled = session(AuctionSessionStatus.SCHEDULED, new BigDecimal("130.00"), 3L);
        AuctionSession opened = session(AuctionSessionStatus.OPEN, new BigDecimal("130.00"), 3L);
        when(sessionRepository.findSessionById(AUCTION_ID)).thenReturn(Optional.of(scheduled));
        when(lifecycleService.advanceToCurrentState(scheduled)).thenReturn(opened);
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(item(AuctionItemReviewStatus.APPROVED)));
        when(itemRepository.findBoundImagesByItemIds(List.of(ITEM_ID)))
                .thenReturn(List.of(image(301L, 0), image(302L, 1)));
        AuctionRegistration registration = registration();
        when(registrationRepository.findByAuctionAndBidder(AUCTION_ID, BUYER_ID))
                .thenReturn(Optional.of(registration));
        when(objectStorage.signRead(any())).thenAnswer(invocation -> {
            ObjectStoragePort.ReadSigningRequest request = invocation.getArgument(0);
            return new ObjectStoragePort.SignedRead(
                    URI.create("https://object-storage.invalid/read/" + request.objectKey()), request.expiresAt()
            );
        });

        AuctionDetailQueryService.AuctionDetail detail = service(true).find(BUYER_ID, AUCTION_ID);

        assertThat(detail.sessionStatus()).isEqualTo(AuctionSessionStatus.OPEN);
        assertThat(detail.displayPrice()).isEqualByComparingTo("130.00");
        assertThat(detail.minimumNextBid()).isEqualByComparingTo("140.00");
        assertThat(detail.images()).extracting(AuctionDetailQueryService.ImageView::sortOrder)
                .containsExactly(0, 1);
        assertThat(detail.images()).allSatisfy(image -> {
            assertThat(image.previewUrl()).isNotNull();
            assertThat(image.previewExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        });
        assertThat(detail.ownedByCurrentUser()).isFalse();
        assertThat(detail.myRegistration().registrationId()).isEqualTo(registration.id());
        assertThat(detail.myRegistration().status()).isEqualTo(AuctionRegistrationStatus.REGISTERED);
    }

    @Test
    void firstBidMinimumEqualsStartPriceAndSellerDoesNotReadRegistration() {
        AuctionSession scheduled = session(AuctionSessionStatus.SCHEDULED, null, 0L);
        when(sessionRepository.findSessionById(AUCTION_ID)).thenReturn(Optional.of(scheduled));
        when(lifecycleService.advanceToCurrentState(scheduled)).thenReturn(scheduled);
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(item(AuctionItemReviewStatus.APPROVED)));
        when(itemRepository.findBoundImagesByItemIds(List.of(ITEM_ID))).thenReturn(List.of());

        AuctionDetailQueryService.AuctionDetail detail = service(false).find(SELLER_ID, AUCTION_ID);

        assertThat(detail.displayPrice()).isEqualByComparingTo("100.00");
        assertThat(detail.minimumNextBid()).isEqualByComparingTo("100.00");
        assertThat(detail.ownedByCurrentUser()).isTrue();
        assertThat(detail.myRegistration()).isNull();
        verifyNoInteractions(registrationRepository, objectStorage);
    }

    @Test
    void hidesDraftSessionAndUnapprovedItemAsNotFound() {
        AuctionSession draft = session(AuctionSessionStatus.DRAFT, null, 0L);
        when(sessionRepository.findSessionById(AUCTION_ID)).thenReturn(Optional.of(draft));
        when(lifecycleService.advanceToCurrentState(draft)).thenReturn(draft);

        assertError(AuctionErrorCode.AUCTION_NOT_FOUND, () -> service(false).find(BUYER_ID, AUCTION_ID));
        verify(itemRepository, never()).findItemById(ITEM_ID);

        AuctionSession open = session(AuctionSessionStatus.OPEN, null, 0L);
        when(sessionRepository.findSessionById(AUCTION_ID)).thenReturn(Optional.of(open));
        when(lifecycleService.advanceToCurrentState(open)).thenReturn(open);
        when(itemRepository.findItemById(ITEM_ID))
                .thenReturn(Optional.of(item(AuctionItemReviewStatus.PENDING_REVIEW)));

        assertError(AuctionErrorCode.AUCTION_NOT_FOUND, () -> service(false).find(BUYER_ID, AUCTION_ID));
    }

    @Test
    void rejectsMissingAuctionAndInvalidIdsBeforeRelatedReads() {
        when(sessionRepository.findSessionById(999L)).thenReturn(Optional.empty());

        assertError(AuctionErrorCode.AUCTION_INVALID, () -> service(false).find(0L, AUCTION_ID));
        assertError(AuctionErrorCode.AUCTION_INVALID, () -> service(false).find(BUYER_ID, 0L));
        assertError(AuctionErrorCode.AUCTION_NOT_FOUND, () -> service(false).find(BUYER_ID, 999L));

        verifyNoInteractions(itemRepository, registrationRepository, objectStorage);
    }

    private AuctionDetailQueryService service(boolean storageEnabled) {
        return new AuctionDetailQueryService(
                sessionRepository, itemRepository, registrationRepository, lifecycleService, objectStorage,
                storageProperties(storageEnabled), Clock.fixed(NOW, ZoneOffset.UTC)
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

    private static AuctionItem item(AuctionItemReviewStatus status) {
        boolean approved = status == AuctionItemReviewStatus.APPROVED;
        return new AuctionItem(
                ITEM_ID, SELLER_ID, "Mechanical keyboard", "A sufficiently detailed item description",
                "ELECTRONICS", AuctionItemCondition.GOOD, status, 1, 2L,
                NOW.minusSeconds(1800), approved ? NOW.minusSeconds(1200) : null,
                NOW.minusSeconds(3600), NOW.minusSeconds(600)
        );
    }

    private static AuctionSession session(
            AuctionSessionStatus status,
            BigDecimal currentPrice,
            long bidCount
    ) {
        return new AuctionSession(
                AUCTION_ID, ITEM_ID, SELLER_ID,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                currentPrice, currentPrice == null ? null : BUYER_ID, bidCount,
                NOW.minusSeconds(60), NOW.plusSeconds(3600), status, 3L,
                NOW.minusSeconds(3600), NOW.minusSeconds(30)
        );
    }

    private static AuctionItemImage image(long imageId, int sortOrder) {
        return new AuctionItemImage(
                imageId, ITEM_ID, SELLER_ID, "dev/users/42/image-" + imageId + ".webp",
                "image.webp", "image/webp", 4096L, null, sortOrder, AuctionImageStatus.BOUND,
                NOW.plusSeconds(600), NOW.minusSeconds(3600), NOW.minusSeconds(300)
        );
    }

    private static AuctionRegistration registration() {
        return new AuctionRegistration(
                401L, "REGISTRATION:detail-test", AUCTION_ID, BUYER_ID, new BigDecimal("50.00"),
                AuctionRegistrationStatus.REGISTERED, null, 1, null, NOW.minusSeconds(120), null, null,
                NOW.minusSeconds(120), 1L, NOW.minusSeconds(180), NOW.minusSeconds(120)
        );
    }

    private static void assertError(AuctionErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }
}

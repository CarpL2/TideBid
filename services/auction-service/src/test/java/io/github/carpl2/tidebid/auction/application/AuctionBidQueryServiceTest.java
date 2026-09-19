package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuctionBidQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-14T08:00:00Z");
    private static final long REQUESTER_ID = 42L;
    private static final long AUCTION_ID = 201L;
    private static final long ITEM_ID = 101L;
    private static final long SELLER_ID = 7L;

    private AuctionSessionRepository sessionRepository;
    private AuctionItemRepository itemRepository;
    private AuctionSessionLifecycleService lifecycleService;
    private AuctionBidQueryService queryService;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AuctionSessionRepository.class);
        itemRepository = mock(AuctionItemRepository.class);
        lifecycleService = mock(AuctionSessionLifecycleService.class);
        queryService = new AuctionBidQueryService(sessionRepository, itemRepository, lifecycleService);
    }

    @Test
    void returnsDescendingPageAndOnlyMarksTheCurrentUsersBids() {
        AuctionSession session = visibleSession(AuctionSessionStatus.OPEN);
        when(sessionRepository.findSessionById(AUCTION_ID)).thenReturn(Optional.of(session));
        when(lifecycleService.advanceToCurrentState(session)).thenReturn(session);
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(approvedItem()));
        when(sessionRepository.findBidsByAuction(AUCTION_ID, 10, 10)).thenReturn(
                new AuctionSessionRepository.BidPage(List.of(
                        bid(302L, 88L, 3L, "130.00", "120.00"),
                        bid(301L, REQUESTER_ID, 2L, "120.00", "100.00")
                ), 12L)
        );

        AuctionBidQueryService.BidPage result = queryService.find(REQUESTER_ID, AUCTION_ID, 2, 10);

        assertThat(result.auctionId()).isEqualTo(AUCTION_ID);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.total()).isEqualTo(12);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.items()).extracting(AuctionBidQueryService.BidView::sequenceNo)
                .containsExactly(3L, 2L);
        assertThat(result.items()).extracting(AuctionBidQueryService.BidView::mine)
                .containsExactly(false, true);
        verify(sessionRepository).findBidsByAuction(AUCTION_ID, 10, 10);
    }

    @Test
    void returnsAnEmptyPageForAVisibleAuctionWithoutBids() {
        AuctionSession scheduled = visibleSession(AuctionSessionStatus.SCHEDULED);
        when(sessionRepository.findSessionById(AUCTION_ID)).thenReturn(Optional.of(scheduled));
        when(lifecycleService.advanceToCurrentState(scheduled)).thenReturn(scheduled);
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(approvedItem()));
        when(sessionRepository.findBidsByAuction(AUCTION_ID, 0, 20))
                .thenReturn(new AuctionSessionRepository.BidPage(List.of(), 0));

        AuctionBidQueryService.BidPage result = queryService.find(REQUESTER_ID, AUCTION_ID, 1, 20);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    void keepsTheWinningBidHistoryVisibleAfterTheAuctionCloses() {
        AuctionSession closed = closedSoldSession();
        when(sessionRepository.findSessionById(AUCTION_ID)).thenReturn(Optional.of(closed));
        when(lifecycleService.advanceToCurrentState(closed)).thenReturn(closed);
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(approvedItem()));
        when(sessionRepository.findBidsByAuction(AUCTION_ID, 0, 20)).thenReturn(
                new AuctionSessionRepository.BidPage(List.of(
                        bid(302L, REQUESTER_ID, 3L, "130.00", "120.00")
                ), 1L)
        );

        AuctionBidQueryService.BidPage result = queryService.find(REQUESTER_ID, AUCTION_ID, 1, 20);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(bid -> {
            assertThat(bid.sequenceNo()).isEqualTo(3L);
            assertThat(bid.mine()).isTrue();
        });
    }

    @Test
    void keepsAnEmptyBidHistoryVisibleAfterAnAuctionClosesUnsold() {
        AuctionSession closed = closedUnsoldSession();
        when(sessionRepository.findSessionById(AUCTION_ID)).thenReturn(Optional.of(closed));
        when(lifecycleService.advanceToCurrentState(closed)).thenReturn(closed);
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(approvedItem()));
        when(sessionRepository.findBidsByAuction(AUCTION_ID, 0, 20))
                .thenReturn(new AuctionSessionRepository.BidPage(List.of(), 0));

        AuctionBidQueryService.BidPage result = queryService.find(REQUESTER_ID, AUCTION_ID, 1, 20);

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void rejectsInvalidPaginationBeforeReadingPersistence() {
        assertThatThrownBy(() -> queryService.find(REQUESTER_ID, AUCTION_ID, 0, 20))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.AUCTION_INVALID));
        assertThatThrownBy(() -> queryService.find(REQUESTER_ID, AUCTION_ID, 1, 101))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(sessionRepository, itemRepository, lifecycleService);
    }

    @Test
    void hidesDraftOrUnapprovedAuctionsAsNotFound() {
        AuctionSession draft = visibleSession(AuctionSessionStatus.DRAFT);
        when(sessionRepository.findSessionById(AUCTION_ID)).thenReturn(Optional.of(draft));
        when(lifecycleService.advanceToCurrentState(draft)).thenReturn(draft);

        assertThatThrownBy(() -> queryService.find(REQUESTER_ID, AUCTION_ID, 1, 20))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.AUCTION_NOT_FOUND));
        verifyNoInteractions(itemRepository);
    }

    @Test
    void rejectsRepositoryRowsThatDoNotRespectTheAuctionAndOrderContract() {
        AuctionSession session = visibleSession(AuctionSessionStatus.OPEN);
        when(sessionRepository.findSessionById(AUCTION_ID)).thenReturn(Optional.of(session));
        when(lifecycleService.advanceToCurrentState(session)).thenReturn(session);
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(approvedItem()));
        when(sessionRepository.findBidsByAuction(AUCTION_ID, 0, 20)).thenReturn(
                new AuctionSessionRepository.BidPage(List.of(
                        bid(301L, 88L, 2L, "120.00", "100.00"),
                        bid(302L, 89L, 3L, "130.00", "120.00")
                ), 2L)
        );

        assertThatThrownBy(() -> queryService.find(REQUESTER_ID, AUCTION_ID, 1, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("descending sequence");
    }

    private static AuctionSession visibleSession(AuctionSessionStatus status) {
        return new AuctionSession(
                AUCTION_ID, ITEM_ID, SELLER_ID,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                new BigDecimal("130.00"), 88L, 3L,
                NOW.minusSeconds(60), NOW.plusSeconds(3600), status, 4L,
                NOW.minusSeconds(3600), NOW
        );
    }

    private static AuctionSession closedSoldSession() {
        return new AuctionSession(
                AUCTION_ID, ITEM_ID, SELLER_ID,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                new BigDecimal("130.00"), REQUESTER_ID, 3L,
                NOW.minusSeconds(3600), NOW.minusSeconds(60), AuctionSessionStatus.CLOSED_SOLD,
                REQUESTER_ID, 302L, new BigDecimal("130.00"), NOW.minusSeconds(30),
                5L, NOW.minusSeconds(7200), NOW.minusSeconds(30)
        );
    }

    private static AuctionSession closedUnsoldSession() {
        return new AuctionSession(
                AUCTION_ID, ITEM_ID, SELLER_ID,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0L,
                NOW.minusSeconds(3600), NOW.minusSeconds(60), AuctionSessionStatus.CLOSED_UNSOLD,
                null, null, null, NOW.minusSeconds(30),
                3L, NOW.minusSeconds(7200), NOW.minusSeconds(30)
        );
    }

    private static AuctionItem approvedItem() {
        return new AuctionItem(
                ITEM_ID, SELLER_ID, "Mechanical keyboard", "A sufficiently detailed item description",
                "ELECTRONICS", AuctionItemCondition.GOOD, AuctionItemReviewStatus.APPROVED,
                1, 2L, NOW.minusSeconds(1800), NOW.minusSeconds(1200),
                NOW.minusSeconds(3600), NOW.minusSeconds(600)
        );
    }

    private static BidRecord bid(
            long id,
            long bidderId,
            long sequenceNo,
            String amount,
            String previousPrice
    ) {
        return new BidRecord(
                id, AUCTION_ID, bidderId, "history-bid-" + id,
                new BigDecimal(amount), new BigDecimal(previousPrice), sequenceNo,
                NOW.plusSeconds(sequenceNo)
        );
    }
}

package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionProxyBidRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBidStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuctionRealtimeSnapshotServiceTest {

    private static final long AUCTION_ID = 501L;
    private static final long USER_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-09-21T03:00:00Z");

    private AuctionSessionRepository sessions;
    private AuctionProxyBidRepository proxies;
    private AuctionSessionLifecycleService lifecycle;
    private AuctionRealtimeSnapshotService service;
    private AuctionSession session;

    @BeforeEach
    void setUp() {
        sessions = mock(AuctionSessionRepository.class);
        proxies = mock(AuctionProxyBidRepository.class);
        lifecycle = mock(AuctionSessionLifecycleService.class);
        service = new AuctionRealtimeSnapshotService(
                sessions, proxies, lifecycle, Clock.fixed(NOW, ZoneOffset.UTC));
        session = new AuctionSession(
                AUCTION_ID, 601L, 7L, money("100.00"), money("10.00"), money("50.00"),
                money("120.00"), USER_ID, 2L, NOW.minusSeconds(600), NOW.plusSeconds(600),
                AuctionSessionStatus.OPEN, 3L, NOW.minusSeconds(10), NOW.minusSeconds(10));
        when(sessions.findSessionById(AUCTION_ID)).thenReturn(Optional.of(session));
        when(lifecycle.advanceToCurrentState(session)).thenReturn(session);
        when(proxies.findByAuctionAndBidder(AUCTION_ID, USER_ID)).thenReturn(Optional.empty());
    }

    @Test
    void returnsAscendingIncrementalPublicBidsAndTrustedPersonalization() {
        when(sessions.findBidsAfterSequence(AUCTION_ID, 0L, 100)).thenReturn(List.of(
                bid(701L, 88L, 1L, "110.00", null),
                bid(702L, USER_ID, 2L, "120.00", "110.00")
        ));

        AuctionRealtimeSnapshotService.Snapshot result = service.find(AUCTION_ID, 0L, 100, USER_ID);

        assertThat(result.bids()).extracting(BidRecord::sequenceNo).containsExactly(1L, 2L);
        assertThat(result.session().bidCount()).isEqualTo(2L);
        assertThat(result.currentUserLeading()).isTrue();
        assertThat(result.currentUserHasProxy()).isFalse();
        assertThat(result.trustedUserId()).isEqualTo(USER_ID);
        assertThat(result.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void returnsCurrentSequenceWhenThereAreNoNewBids() {
        when(sessions.findBidsAfterSequence(AUCTION_ID, 2L, 100)).thenReturn(List.of());

        AuctionRealtimeSnapshotService.Snapshot result = service.find(AUCTION_ID, 2L, 100, null);

        assertThat(result.bids()).isEmpty();
        assertThat(result.session().bidCount()).isEqualTo(2L);
    }

    @Test
    void rejectsAnIncrementalWindowAboveTheProtocolLimit() {
        assertThatThrownBy(() -> service.find(AUCTION_ID, 0L, 101, null))
                .hasMessageContaining("limit must be between 1 and 100");
    }

    private static BidRecord bid(long id, long bidderId, long sequence, String amount, String previous) {
        return new BidRecord(id, AUCTION_ID, bidderId, "snapshot-" + sequence,
                io.github.carpl2.tidebid.auction.domain.BidSource.MANUAL, null,
                money(amount), previous == null ? null : money(previous), sequence, NOW);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}

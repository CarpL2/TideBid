package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionBidCommandRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionBidCommandTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionProxyBidRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.IdGenerator;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommand;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandType;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBid;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBidStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.auction.domain.BidSource;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionBidServiceTest {

    private static final long AUCTION_ID = 101L;
    private static final long BIDDER_ID = 201L;
    private static final String REQUEST_ID = "manual-bid-0001";
    private static final Instant NOW = Instant.parse("2026-09-21T05:00:00.123456Z");

    private AuctionSessionRepository sessions;
    private AuctionRegistrationRepository registrations;
    private AuctionProxyBidRepository proxies;
    private AuctionBidCommandRepository commands;
    private AuctionBidCommandTransaction transaction;
    private AuctionSessionLifecycleService lifecycle;
    private IdGenerator ids;
    private AuctionBidService service;

    @BeforeEach
    void setUp() {
        sessions = mock(AuctionSessionRepository.class);
        registrations = mock(AuctionRegistrationRepository.class);
        proxies = mock(AuctionProxyBidRepository.class);
        commands = mock(AuctionBidCommandRepository.class);
        transaction = mock(AuctionBidCommandTransaction.class);
        lifecycle = mock(AuctionSessionLifecycleService.class);
        ids = mock(IdGenerator.class);
        service = new AuctionBidService(sessions, registrations, proxies, commands, transaction,
                lifecycle, ids, Clock.fixed(NOW, ZoneOffset.UTC));
        when(commands.findByActorAndRequest(BIDDER_ID, REQUEST_ID)).thenReturn(Optional.empty());
        when(registrations.findByAuctionAndBidder(AUCTION_ID, BIDDER_ID)).thenReturn(Optional.of(registration()));
        when(lifecycle.advanceToCurrentState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(proxies.findActiveByAuction(AUCTION_ID)).thenReturn(List.of());
        when(ids.nextId()).thenReturn(301L, 302L, 303L, 304L, 305L, 306L, 307L, 308L);
        when(transaction.commit(any())).thenAnswer(invocation -> committed(invocation.getArgument(0)));
    }

    @Test
    void acceptsManualBidThroughUnifiedCommandTransaction() {
        AuctionSession before = session(null, null, 0, 7, NOW.plusSeconds(300));
        AuctionSession latest = session(money("100.00"), BIDDER_ID, 1, 8, NOW.plusSeconds(300));
        when(sessions.findSessionById(AUCTION_ID)).thenReturn(Optional.of(before), Optional.of(latest));

        AuctionBidService.Result result = service.place(command("100.00"));

        assertThat(result.leading()).isTrue();
        assertThat(result.outbidByProxy()).isFalse();
        assertThat(result.bids()).hasSize(1);
        assertThat(result.bids().getFirst().source()).isEqualTo(BidSource.MANUAL);
        assertThat(result.session()).isSameAs(latest);
    }

    @Test
    void reportsImmediateProxyResponseWithoutExposingTheMaximum() {
        AuctionSession before = session(null, null, 0, 7, NOW.plusSeconds(300));
        AuctionSession latest = session(money("210.00"), 901L, 2, 9, NOW.plusSeconds(300));
        when(sessions.findSessionById(AUCTION_ID)).thenReturn(Optional.of(before), Optional.of(latest));
        when(proxies.findActiveByAuction(AUCTION_ID)).thenReturn(List.of(proxy(901L, "500.00")));

        AuctionBidService.Result result = service.place(command("200.00"));

        assertThat(result.leading()).isFalse();
        assertThat(result.outbidByProxy()).isTrue();
        assertThat(result.bids()).extracting(BidRecord::source)
                .containsExactly(BidSource.MANUAL, BidSource.PROXY);
        assertThat(result.bids()).extracting(BidRecord::amount)
                .containsExactly(money("200.00"), money("210.00"));
    }

    @Test
    void retriesCasByReloadingAndReplanning() {
        AuctionSession first = session(null, null, 0, 7, NOW.plusSeconds(300));
        AuctionSession second = session(money("110.00"), 901L, 1, 8, NOW.plusSeconds(300));
        AuctionSession latest = session(money("200.00"), BIDDER_ID, 2, 9, NOW.plusSeconds(300));
        when(sessions.findSessionById(AUCTION_ID))
                .thenReturn(Optional.of(first), Optional.of(second), Optional.of(latest));
        doThrow(new AuctionBidCommandTransaction.BidConflictException())
                .doAnswer(invocation -> committed(invocation.getArgument(0)))
                .when(transaction).commit(any());

        AuctionBidService.Result result = service.place(command("200.00"));

        assertThat(result.leading()).isTrue();
        assertThat(result.bids().getFirst().previousPrice()).isEqualByComparingTo("110.00");
        verify(transaction, times(2)).commit(any());
    }

    @Test
    void returnsStoredCommandAndBidsForIdempotentReplay() throws Exception {
        AuctionSession latest = session(money("210.00"), 901L, 2, 9, NOW.plusSeconds(300));
        AuctionBidCommand stored = new AuctionBidCommand(
                301L, AUCTION_ID, BIDDER_ID, REQUEST_ID, AuctionBidCommandType.MANUAL_BID,
                hash(AUCTION_ID + ":200.00"), AuctionBidCommandStatus.SUCCEEDED, 2,
                money("210.00"), false, 1L, 2L, NOW, NOW);
        List<BidRecord> bids = List.of(
                bid(302L, BIDDER_ID, BidSource.MANUAL, "200.00", null, 1L, stored.id()),
                bid(303L, 901L, BidSource.PROXY, "210.00", "200.00", 2L, stored.id()));
        when(commands.findByActorAndRequest(BIDDER_ID, REQUEST_ID)).thenReturn(Optional.of(stored));
        when(sessions.findBidsByCommandId(stored.id())).thenReturn(bids);
        when(sessions.findSessionById(AUCTION_ID)).thenReturn(Optional.of(latest));

        AuctionBidService.Result result = service.place(command("200.00"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.outbidByProxy()).isTrue();
        assertThat(result.bids()).isEqualTo(bids);
        verify(transaction, times(0)).commit(any());
    }

    @Test
    void rejectsRequestIdPayloadConflict() throws Exception {
        AuctionBidCommand stored = new AuctionBidCommand(
                301L, AUCTION_ID, BIDDER_ID, REQUEST_ID, AuctionBidCommandType.MANUAL_BID,
                hash(AUCTION_ID + ":100.00"), AuctionBidCommandStatus.SUCCEEDED, 1,
                money("100.00"), true, 1L, 1L, NOW, NOW);
        when(commands.findByActorAndRequest(BIDDER_ID, REQUEST_ID)).thenReturn(Optional.of(stored));

        assertBusinessError(() -> service.place(command("200.00")), AuctionErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void rejectsSellerAndUnregisteredBidderBeforeTransaction() {
        AuctionSession sellerOwned = new AuctionSession(
                AUCTION_ID, 102L, BIDDER_ID, money("100.00"), money("10.00"), money("50.00"),
                null, null, 0, NOW.minusSeconds(60), NOW.plusSeconds(300),
                AuctionSessionStatus.OPEN, 7, NOW.minusSeconds(120), NOW.minusSeconds(1));
        when(sessions.findSessionById(AUCTION_ID)).thenReturn(Optional.of(sellerOwned));

        assertBusinessError(() -> service.place(command("100.00")), AuctionErrorCode.SELLER_CANNOT_PARTICIPATE);

        AuctionSession ordinary = session(null, null, 0, 7, NOW.plusSeconds(300));
        when(sessions.findSessionById(AUCTION_ID)).thenReturn(Optional.of(ordinary));
        when(registrations.findByAuctionAndBidder(AUCTION_ID, BIDDER_ID)).thenReturn(Optional.empty());
        assertBusinessError(() -> service.place(command("100.00")), AuctionErrorCode.REGISTRATION_REQUIRED);
    }

    private static AuctionBidCommandTransaction.CommittedCommand committed(
            AuctionBidCommandTransaction.CommitRequest request
    ) {
        return new AuctionBidCommandTransaction.CommittedCommand(request.completedCommand(), request.bids());
    }

    private static AuctionSession session(
            BigDecimal currentPrice, Long currentBidder, long bidCount, long version, Instant endAt
    ) {
        return new AuctionSession(
                AUCTION_ID, 102L, 401L, money("100.00"), money("10.00"), money("50.00"),
                currentPrice, currentBidder, bidCount, NOW.minusSeconds(60), endAt,
                AuctionSessionStatus.OPEN, version, NOW.minusSeconds(120), NOW.minusSeconds(1));
    }

    private static AuctionRegistration registration() {
        return new AuctionRegistration(
                501L, "REGISTRATION:501", AUCTION_ID, BIDDER_ID, money("50.00"),
                AuctionRegistrationStatus.REGISTERED, null, 1, null, NOW.minusSeconds(30),
                null, null, NOW.minusSeconds(30), 1, NOW.minusSeconds(60), NOW.minusSeconds(30));
    }

    private static AuctionProxyBid proxy(long bidderId, String maximum) {
        return new AuctionProxyBid(801L, AUCTION_ID, bidderId, money(maximum), AuctionProxyBidStatus.ACTIVE,
                1, 0, null, NOW.minusSeconds(30), NOW.minusSeconds(30));
    }

    private static BidRecord bid(
            long id, long bidderId, BidSource source, String amount, String previous, long sequence, long commandId
    ) {
        return new BidRecord(id, AUCTION_ID, bidderId, REQUEST_ID, source, commandId, money(amount),
                previous == null ? null : money(previous), sequence, NOW);
    }

    private static AuctionBidService.PlaceBidCommand command(String amount) {
        return new AuctionBidService.PlaceBidCommand(BIDDER_ID, AUCTION_ID, REQUEST_ID, money(amount));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertBusinessError(Runnable invocation, AuctionErrorCode expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }
}

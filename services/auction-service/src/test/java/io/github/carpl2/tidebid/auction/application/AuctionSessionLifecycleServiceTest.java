package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionSessionLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T03:00:00.123456789Z");
    private static final Instant TRANSITIONED_AT = Instant.parse("2026-09-13T03:00:00.123456Z");

    private AuctionSessionRepository repository;
    private AuctionSessionLifecycleService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuctionSessionRepository.class);
        service = new AuctionSessionLifecycleService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void leavesScheduledSessionUntouchedBeforeStart() {
        AuctionSession scheduled = session(
                101L, AuctionSessionStatus.SCHEDULED,
                TRANSITIONED_AT.plusSeconds(1), TRANSITIONED_AT.plusSeconds(3600), 5L
        );

        assertThat(service.advanceToCurrentState(scheduled)).isSameAs(scheduled);

        verify(repository, never()).openScheduledSession(anyLong(), anyLong(), any());
        verify(repository, never()).markOpenSessionAwaitingClose(anyLong(), anyLong(), any());
        verify(repository, never()).findSessionById(anyLong());
    }

    @Test
    void opensAtTheExactStartInstantAndReturnsDatabaseTruth() {
        AuctionSession scheduled = session(
                102L, AuctionSessionStatus.SCHEDULED,
                TRANSITIONED_AT, TRANSITIONED_AT.plusSeconds(3600), 8L
        );
        AuctionSession opened = withState(scheduled, AuctionSessionStatus.OPEN, 9L);
        when(repository.openScheduledSession(102L, 8L, TRANSITIONED_AT)).thenReturn(true);
        when(repository.findSessionById(102L)).thenReturn(Optional.of(opened));

        assertThat(service.advanceToCurrentState(scheduled)).isEqualTo(opened);

        verify(repository).openScheduledSession(102L, 8L, TRANSITIONED_AT);
        verify(repository, never()).markOpenSessionAwaitingClose(anyLong(), anyLong(), any());
    }

    @Test
    void reloadsTheOpenWinnerWhenAnotherInstanceWinsOpeningCas() {
        AuctionSession stale = session(
                103L, AuctionSessionStatus.SCHEDULED,
                TRANSITIONED_AT.minusSeconds(1), TRANSITIONED_AT.plusSeconds(3600), 2L
        );
        AuctionSession opened = withState(stale, AuctionSessionStatus.OPEN, 3L);
        when(repository.openScheduledSession(103L, 2L, TRANSITIONED_AT)).thenReturn(false);
        when(repository.findSessionById(103L)).thenReturn(Optional.of(opened));

        assertThat(service.advanceToCurrentState(stale)).isEqualTo(opened);
    }

    @Test
    void leavesOpenSessionUntouchedBeforeEnd() {
        AuctionSession opened = session(
                104L, AuctionSessionStatus.OPEN,
                TRANSITIONED_AT.minusSeconds(3600), TRANSITIONED_AT.plusNanos(1), 4L
        );

        assertThat(service.advanceToCurrentState(opened)).isSameAs(opened);

        verify(repository, never()).openScheduledSession(anyLong(), anyLong(), any());
        verify(repository, never()).markOpenSessionAwaitingClose(anyLong(), anyLong(), any());
        verify(repository, never()).findSessionById(anyLong());
    }

    @Test
    void marksOpenSessionAwaitingCloseAtTheExactEndInstant() {
        AuctionSession opened = session(
                105L, AuctionSessionStatus.OPEN,
                TRANSITIONED_AT.minusSeconds(3600), TRANSITIONED_AT, 7L
        );
        AuctionSession awaitingClose = withState(opened, AuctionSessionStatus.AWAITING_CLOSE, 8L);
        when(repository.markOpenSessionAwaitingClose(105L, 7L, TRANSITIONED_AT)).thenReturn(true);
        when(repository.findSessionById(105L)).thenReturn(Optional.of(awaitingClose));

        assertThat(service.advanceToCurrentState(opened)).isEqualTo(awaitingClose);

        verify(repository).markOpenSessionAwaitingClose(105L, 7L, TRANSITIONED_AT);
    }

    @Test
    void catchesUpScheduledSessionPastEndWithoutSkippingStates() {
        AuctionSession scheduled = session(
                106L, AuctionSessionStatus.SCHEDULED,
                TRANSITIONED_AT.minusSeconds(7200), TRANSITIONED_AT.minusSeconds(1), 10L
        );
        AuctionSession opened = withState(scheduled, AuctionSessionStatus.OPEN, 11L);
        AuctionSession awaitingClose = withState(scheduled, AuctionSessionStatus.AWAITING_CLOSE, 12L);
        when(repository.openScheduledSession(106L, 10L, TRANSITIONED_AT)).thenReturn(true);
        when(repository.findSessionById(106L))
                .thenReturn(Optional.of(opened))
                .thenReturn(Optional.of(awaitingClose));
        when(repository.markOpenSessionAwaitingClose(106L, 11L, TRANSITIONED_AT)).thenReturn(true);

        assertThat(service.advanceToCurrentState(scheduled)).isEqualTo(awaitingClose);

        verify(repository).openScheduledSession(106L, 10L, TRANSITIONED_AT);
        verify(repository).markOpenSessionAwaitingClose(106L, 11L, TRANSITIONED_AT);
    }

    @Test
    void reloadsAwaitingCloseWinnerWhenAnotherInstanceWinsClosingCas() {
        AuctionSession stale = session(
                107L, AuctionSessionStatus.OPEN,
                TRANSITIONED_AT.minusSeconds(3600), TRANSITIONED_AT.minusSeconds(1), 12L
        );
        AuctionSession awaitingClose = withState(stale, AuctionSessionStatus.AWAITING_CLOSE, 13L);
        when(repository.markOpenSessionAwaitingClose(107L, 12L, TRANSITIONED_AT)).thenReturn(false);
        when(repository.findSessionById(107L)).thenReturn(Optional.of(awaitingClose));

        assertThat(service.advanceToCurrentState(stale)).isEqualTo(awaitingClose);
    }

    @Test
    void awaitingCloseIsTerminalForStageTwoLifecycle() {
        AuctionSession awaitingClose = session(
                108L, AuctionSessionStatus.AWAITING_CLOSE,
                TRANSITIONED_AT.minusSeconds(7200), TRANSITIONED_AT.minusSeconds(3600), 14L
        );

        assertThat(service.advanceToCurrentState(awaitingClose)).isSameAs(awaitingClose);

        verify(repository, never()).openScheduledSession(anyLong(), anyLong(), any());
        verify(repository, never()).markOpenSessionAwaitingClose(anyLong(), anyLong(), any());
        verify(repository, never()).findSessionById(anyLong());
    }

    private static AuctionSession session(
            long auctionId,
            AuctionSessionStatus status,
            Instant startAt,
            Instant endAt,
            long version
    ) {
        return new AuctionSession(
                auctionId, auctionId + 1000, auctionId + 2000,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0,
                startAt, endAt, status, version,
                startAt.minus(Duration.ofHours(1)), TRANSITIONED_AT.minusSeconds(1)
        );
    }

    private static AuctionSession withState(
            AuctionSession source,
            AuctionSessionStatus status,
            long version
    ) {
        return new AuctionSession(
                source.id(), source.itemId(), source.sellerId(),
                source.startPrice(), source.bidIncrement(), source.depositAmount(),
                source.currentPrice(), source.currentBidderId(), source.bidCount(),
                source.startAt(), source.endAt(), status, version, source.createdAt(), TRANSITIONED_AT
        );
    }
}

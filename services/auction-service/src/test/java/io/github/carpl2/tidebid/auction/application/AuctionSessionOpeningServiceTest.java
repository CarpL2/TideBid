package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionTimingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionSessionOpeningServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T02:00:00.123456789Z");
    private static final Instant OPENED_AT = Instant.parse("2026-09-13T02:00:00.123456Z");

    private AuctionSessionRepository repository;
    private AuctionSessionOpeningService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuctionSessionRepository.class);
        service = new AuctionSessionOpeningService(
                repository,
                new AuctionTimingProperties(
                        Duration.ofMinutes(1), Duration.ofDays(7), true, Duration.ofSeconds(1), 2
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void opensDueCandidatesWithOneSharedClockSnapshot() {
        AuctionSession first = session(101L, 3L, OPENED_AT.minusSeconds(2));
        AuctionSession second = session(102L, 7L, OPENED_AT.minusSeconds(1));
        when(repository.findDueScheduledSessions(OPENED_AT, 2)).thenReturn(List.of(first, second));
        when(repository.openScheduledSession(first.id(), first.version(), OPENED_AT)).thenReturn(true);
        when(repository.openScheduledSession(second.id(), second.version(), OPENED_AT)).thenReturn(false);

        assertThat(service.openDueSessions())
                .isEqualTo(new AuctionSessionOpeningService.OpeningResult(2, 1, 1));

        verify(repository).openScheduledSession(first.id(), first.version(), OPENED_AT);
        verify(repository).openScheduledSession(second.id(), second.version(), OPENED_AT);
    }

    @Test
    void emptyScanDoesNotAttemptAnyStateUpdate() {
        when(repository.findDueScheduledSessions(OPENED_AT, 2)).thenReturn(List.of());

        assertThat(service.openDueSessions())
                .isEqualTo(new AuctionSessionOpeningService.OpeningResult(0, 0, 0));
        verify(repository, never()).openScheduledSession(anyLong(), anyLong(), any());
    }

    @Test
    void databaseFailureIsVisibleToTheScheduler() {
        when(repository.findDueScheduledSessions(OPENED_AT, 2))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(service::openDueSessions)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }

    private static AuctionSession session(long auctionId, long version, Instant startAt) {
        return new AuctionSession(
                auctionId, auctionId + 1000, auctionId + 2000,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0,
                startAt, startAt.plus(Duration.ofHours(2)), AuctionSessionStatus.SCHEDULED,
                version, startAt.minusSeconds(60), startAt.minusSeconds(30)
        );
    }

}

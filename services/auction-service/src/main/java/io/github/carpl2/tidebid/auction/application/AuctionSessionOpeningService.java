package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionTimingProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Profile({"local-db", "nacos"})
public class AuctionSessionOpeningService {

    private final AuctionSessionRepository sessionRepository;
    private final AuctionTimingProperties timingProperties;
    private final Clock clock;

    public AuctionSessionOpeningService(
            AuctionSessionRepository sessionRepository,
            AuctionTimingProperties timingProperties,
            Clock clock
    ) {
        this.sessionRepository = sessionRepository;
        this.timingProperties = timingProperties;
        this.clock = clock;
    }

    public OpeningResult openDueSessions() {
        Instant openedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<AuctionSession> candidates = sessionRepository.findDueScheduledSessions(
                openedAt,
                timingProperties.openingScanBatchSize()
        );

        int opened = 0;
        for (AuctionSession candidate : candidates) {
            if (sessionRepository.openScheduledSession(candidate.id(), candidate.version(), openedAt)) {
                opened++;
            }
        }
        return new OpeningResult(candidates.size(), opened, candidates.size() - opened);
    }

    public AuctionSession openIfDue(AuctionSession snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        Instant openedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (snapshot.status() != AuctionSessionStatus.SCHEDULED
                || openedAt.isBefore(snapshot.startAt())) {
            return snapshot;
        }

        sessionRepository.openScheduledSession(snapshot.id(), snapshot.version(), openedAt);
        return sessionRepository.findSessionById(snapshot.id())
                .orElseThrow(() -> new IllegalStateException("Auction session disappeared during lazy opening"));
    }

    public record OpeningResult(int candidates, int opened, int unchanged) {
        public OpeningResult {
            if (candidates < 0 || opened < 0 || unchanged < 0 || opened + unchanged != candidates) {
                throw new IllegalArgumentException("opening counters are inconsistent");
            }
        }
    }
}

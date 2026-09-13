package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@Profile({"local-db", "nacos"})
public class AuctionSessionLifecycleService {

    private final AuctionSessionRepository sessionRepository;
    private final Clock clock;

    public AuctionSessionLifecycleService(AuctionSessionRepository sessionRepository, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.clock = clock;
    }

    public AuctionSession advanceToCurrentState(AuctionSession snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        Instant transitionedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionSession current = snapshot;

        if (current.status() == AuctionSessionStatus.SCHEDULED
                && !transitionedAt.isBefore(current.startAt())) {
            sessionRepository.openScheduledSession(current.id(), current.version(), transitionedAt);
            current = reload(current.id());
        }

        if (current.status() == AuctionSessionStatus.OPEN
                && !transitionedAt.isBefore(current.endAt())) {
            sessionRepository.markOpenSessionAwaitingClose(current.id(), current.version(), transitionedAt);
            current = reload(current.id());
        }
        return current;
    }

    private AuctionSession reload(long auctionId) {
        return sessionRepository.findSessionById(auctionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Auction session disappeared during lifecycle transition"
                ));
    }
}

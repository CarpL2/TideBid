package io.github.carpl2.tidebid.auction.infrastructure.scheduling;

import io.github.carpl2.tidebid.auction.application.AuctionClosingService;
import io.github.carpl2.tidebid.auction.application.port.AuctionClosingTransaction.CloseResult;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionCloseSchedulingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Component
@Profile({"local-db", "nacos"})
@ConditionalOnProperty(
        prefix = "tidebid.auction.close-scheduling",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AuctionDueClosingJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuctionDueClosingJob.class);

    private final JdbcTemplate jdbc;
    private final AuctionClosingService closingService;
    private final AuctionCloseSchedulingProperties properties;
    private final Clock clock;

    public AuctionDueClosingJob(
            DataSource dataSource,
            AuctionClosingService closingService,
            AuctionCloseSchedulingProperties properties,
            Clock clock
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.closingService = closingService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${tidebid.auction.close-scheduling.scan-interval:10s}",
            fixedDelayString = "${tidebid.auction.close-scheduling.scan-interval:10s}"
    )
    public int closeDueAuctions() {
        Instant now = clock.instant();
        List<DueAuction> due = jdbc.query("""
                SELECT id, end_at
                FROM auction_session
                WHERE status IN ('SCHEDULED', 'OPEN', 'AWAITING_CLOSE')
                  AND end_at <= ?
                ORDER BY end_at, id
                LIMIT ?
                """, (row, rowNumber) -> new DueAuction(
                        row.getLong("id"), row.getTimestamp("end_at").toInstant()),
                Timestamp.from(now), properties.batchSize());
        int closed = 0;
        for (DueAuction auction : due) {
            try {
                CloseResult result = closingService.fromDatabaseScan(auction.id(), auction.endAt());
                if (result == CloseResult.CLOSED_SOLD || result == CloseResult.CLOSED_UNSOLD) {
                    closed++;
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Due auction close deferred: auctionId={}, errorCode={}",
                        auction.id(), exception.getClass().getSimpleName());
            }
        }
        return closed;
    }

    private record DueAuction(long id, Instant endAt) { }
}

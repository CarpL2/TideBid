package io.github.carpl2.tidebid.auction.infrastructure.scheduling;

import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionCloseSchedulingProperties;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.AuctionOutboxEventFactory;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.JdbcAuctionOutboxRepository;
import io.github.carpl2.tidebid.contracts.CloseAuctionCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Repairs scheduled auctions whose close command was absent, without exposing an administrative API. */
@Component
@Profile({"local-db", "nacos"})
@ConditionalOnProperty(
        prefix = "tidebid.auction.close-scheduling",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AuctionCloseCommandReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuctionCloseCommandReconciler.class);

    private final JdbcTemplate jdbc;
    private final JdbcAuctionOutboxRepository outbox;
    private final AuctionOutboxEventFactory eventFactory;
    private final AuctionCloseSchedulingProperties properties;
    private final Clock clock;

    public AuctionCloseCommandReconciler(
            DataSource dataSource,
            JdbcAuctionOutboxRepository outbox,
            AuctionOutboxEventFactory eventFactory,
            AuctionCloseSchedulingProperties properties,
            Clock clock
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.outbox = outbox;
        this.eventFactory = eventFactory;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${tidebid.auction.close-scheduling.scan-interval:10s}",
            fixedDelayString = "${tidebid.auction.close-scheduling.scan-interval:10s}"
    )
    @Transactional
    public int reconcile() {
        List<MissingCloseCommand> missing = jdbc.query("""
                SELECT session.id, session.end_at, session.updated_at
                FROM auction_session session
                WHERE session.status IN ('SCHEDULED', 'OPEN', 'AWAITING_CLOSE')
                  AND NOT EXISTS (
                      SELECT 1
                      FROM auction_outbox message
                      WHERE message.aggregate_type = 'AUCTION'
                        AND message.aggregate_id = CAST(session.id AS CHAR)
                        AND message.event_type = ?
                  )
                ORDER BY session.id
                LIMIT ?
                """,
                (row, rowNumber) -> new MissingCloseCommand(
                        row.getLong("id"),
                        row.getTimestamp("end_at").toInstant(),
                        row.getTimestamp("updated_at").toInstant()),
                CloseAuctionCommand.EVENT_TYPE,
                properties.batchSize());

        int repaired = 0;
        for (MissingCloseCommand command : missing) {
            if (outbox.enqueueIfAbsent(
                    eventFactory.closeAuction(command.auctionId(), command.expectedEndAt(), command.scheduledAt()),
                    clock.instant())) {
                repaired++;
                LOGGER.info("Missing close command repaired: auctionId={}, expectedEndAt={}",
                        command.auctionId(), command.expectedEndAt());
            }
        }
        return repaired;
    }

    private record MissingCloseCommand(long auctionId, Instant expectedEndAt, Instant scheduledAt) { }
}

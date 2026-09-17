package io.github.carpl2.tidebid.auction.infrastructure.messaging;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Idempotent consumer marker stored in this service's own database.
 *
 * <p>Call this method inside the same transaction as the business update and any resulting outbox
 * insert. A replay with identical immutable metadata is harmless; reusing an event ID with
 * different metadata is a permanent contract conflict.</p>
 */
@Repository
@Profile({"local-db", "nacos"})
public class JdbcAuctionInboxRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcAuctionInboxRepository.class);
    private static final String TABLE = "auction_inbox";

    private final JdbcTemplate jdbc;

    public JdbcAuctionInboxRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public InboxDecision recordProcessed(InboxEntity incoming) {
        Objects.requireNonNull(incoming, "incoming must not be null");
        try {
            jdbc.update("""
                    INSERT INTO %s (
                        id, consumer_name, event_id, event_type, schema_version,
                        payload_hash, processed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.formatted(TABLE),
                    IdWorker.getId(),
                    incoming.consumerName(),
                    incoming.eventId(),
                    incoming.eventType(),
                    incoming.schemaVersion(),
                    incoming.payloadHash(),
                    Timestamp.from(incoming.processedAt()));
            return InboxDecision.INSERTED;
        } catch (DuplicateKeyException duplicate) {
            List<InboxEntity> existing = jdbc.query("""
                    SELECT consumer_name, event_id, event_type, schema_version, payload_hash, processed_at
                    FROM %s
                    WHERE consumer_name = ? AND event_id = ?
                    """.formatted(TABLE),
                    (row, rowNumber) -> new InboxEntity(
                            row.getString("consumer_name"),
                            row.getString("event_id"),
                            row.getString("event_type"),
                            row.getInt("schema_version"),
                            row.getString("payload_hash"),
                            row.getTimestamp("processed_at").toInstant()),
                    incoming.consumerName(),
                    incoming.eventId());
            if (existing.size() == 1 && existing.getFirst().sameEnvelopeAs(incoming)) {
                return InboxDecision.DUPLICATE;
            }
            LOGGER.warn("Rejected conflicting inbox replay: consumerName={}, eventId={}",
                    incoming.consumerName(), incoming.eventId());
            throw new InboxReplayConflictException(incoming.consumerName(), incoming.eventId(), duplicate);
        }
    }

    public record InboxEntity(
            String consumerName,
            String eventId,
            String eventType,
            int schemaVersion,
            String payloadHash,
            Instant processedAt
    ) {
        public InboxEntity {
            Objects.requireNonNull(processedAt, "processedAt must not be null");
        }

        private boolean sameEnvelopeAs(InboxEntity other) {
            return Objects.equals(eventType, other.eventType)
                    && schemaVersion == other.schemaVersion
                    && Objects.equals(payloadHash, other.payloadHash);
        }
    }

    public enum InboxDecision {
        INSERTED,
        DUPLICATE
    }

    public static final class InboxReplayConflictException extends RuntimeException {
        public InboxReplayConflictException(String consumerName, String eventId, Throwable cause) {
            super("eventId was reused with different immutable metadata for consumer "
                    + consumerName + ": " + eventId, cause);
        }
    }
}

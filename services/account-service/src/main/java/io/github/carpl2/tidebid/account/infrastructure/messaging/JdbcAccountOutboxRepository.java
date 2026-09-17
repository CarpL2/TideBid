package io.github.carpl2.tidebid.account.infrastructure.messaging;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.carpl2.tidebid.account.infrastructure.config.AccountOutboxProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional outbox persistence for this service's own schema.
 *
 * <p>The caller writes a pending row inside its business transaction. Publishers later compete
 * for short leases through conditional updates; completion always checks the lease token so a
 * timed-out worker cannot acknowledge a lease that has already been reclaimed.</p>
 */
@Repository
@Profile({"local-db", "nacos"})
public class JdbcAccountOutboxRepository {

    private static final String TABLE = "account_outbox";

    private final JdbcTemplate jdbc;
    private final AccountOutboxProperties properties;

    public JdbcAccountOutboxRepository(DataSource dataSource, AccountOutboxProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.properties = properties;
    }

    public long enqueue(NewOutboxEvent event, Instant now) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(now, "now must not be null");
        long id = IdWorker.getId();
        jdbc.update("""
                INSERT INTO %s (
                    id, event_id, aggregate_type, aggregate_id, event_type, schema_version,
                    topic, tag, message_key, payload, payload_hash, deliver_at, status,
                    attempt_count, next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, 'PENDING', 0, ?, ?, ?)
                """.formatted(TABLE),
                id,
                event.eventId(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.schemaVersion(),
                event.topic(),
                event.eventType(),
                event.eventId(),
                event.payload(),
                event.payloadHash(),
                timestamp(event.deliverAt()),
                timestamp(now),
                timestamp(now),
                timestamp(now));
        return id;
    }

    public List<OutboxEntity> claimBatch(String leaseOwner, Instant now) {
        requireText(leaseOwner, 64, "leaseOwner");
        Objects.requireNonNull(now, "now must not be null");
        Instant publishableThrough = now.plus(properties.delaySafeHorizon());
        List<String> candidates = jdbc.queryForList("""
                SELECT event_id
                FROM %s
                WHERE deliver_at <= ?
                  AND ((status = 'PENDING' AND next_attempt_at <= ?)
                    OR (status = 'PUBLISHING' AND lease_until <= ?))
                ORDER BY deliver_at, id
                LIMIT ?
                """.formatted(TABLE),
                String.class,
                timestamp(publishableThrough),
                timestamp(now),
                timestamp(now),
                properties.batchSize());

        List<OutboxEntity> claimed = new ArrayList<>(candidates.size());
        for (String eventId : candidates) {
            String leaseToken = UUID.randomUUID().toString();
            Instant leaseUntil = now.plus(properties.leaseDuration());
            int changed = jdbc.update("""
                    UPDATE %s
                    SET status = 'PUBLISHING',
                        lease_owner = ?,
                        lease_token = ?,
                        lease_until = ?,
                        updated_at = ?
                    WHERE event_id = ?
                      AND deliver_at <= ?
                      AND ((status = 'PENDING' AND next_attempt_at <= ?)
                        OR (status = 'PUBLISHING' AND lease_until <= ?))
                    """.formatted(TABLE),
                    leaseOwner,
                    leaseToken,
                    timestamp(leaseUntil),
                    timestamp(now),
                    eventId,
                    timestamp(publishableThrough),
                    timestamp(now),
                    timestamp(now));
            if (changed == 1) {
                findByLeaseToken(leaseToken).ifPresent(claimed::add);
            }
        }
        return List.copyOf(claimed);
    }

    public boolean markPublished(String eventId, String leaseToken, Instant publishedAt) {
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        return jdbc.update("""
                UPDATE %s
                SET status = 'PUBLISHED',
                    attempt_count = attempt_count + 1,
                    lease_owner = NULL,
                    lease_token = NULL,
                    lease_until = NULL,
                    published_at = ?,
                    last_error_code = NULL,
                    updated_at = ?
                WHERE event_id = ?
                  AND status = 'PUBLISHING'
                  AND lease_token = ?
                """.formatted(TABLE),
                timestamp(publishedAt),
                timestamp(publishedAt),
                eventId,
                leaseToken) == 1;
    }

    public FailureResult markFailed(String eventId, String leaseToken, String errorCode, Instant failedAt) {
        requireText(errorCode, 96, "errorCode");
        Objects.requireNonNull(failedAt, "failedAt must not be null");
        Optional<Integer> currentAttempts = jdbc.query("""
                SELECT attempt_count
                FROM %s
                WHERE event_id = ?
                  AND status = 'PUBLISHING'
                  AND lease_token = ?
                """.formatted(TABLE),
                (rs, rowNum) -> rs.getInt(1),
                eventId,
                leaseToken).stream().findFirst();
        if (currentAttempts.isEmpty()) {
            return FailureResult.STALE_LEASE;
        }

        int completedAttempts = currentAttempts.get() + 1;
        boolean exhausted = completedAttempts >= properties.maximumAttempts();
        Instant nextAttemptAt = exhausted ? failedAt : failedAt.plus(backoffFor(completedAttempts));
        int changed = jdbc.update("""
                UPDATE %s
                SET status = ?,
                    attempt_count = ?,
                    next_attempt_at = ?,
                    lease_owner = NULL,
                    lease_token = NULL,
                    lease_until = NULL,
                    last_error_code = ?,
                    updated_at = ?
                WHERE event_id = ?
                  AND status = 'PUBLISHING'
                  AND lease_token = ?
                """.formatted(TABLE),
                exhausted ? "DEAD" : "PENDING",
                completedAttempts,
                timestamp(nextAttemptAt),
                errorCode,
                timestamp(failedAt),
                eventId,
                leaseToken);
        if (changed != 1) {
            return FailureResult.STALE_LEASE;
        }
        return exhausted ? FailureResult.DEAD : FailureResult.RETRY_SCHEDULED;
    }

    public Optional<OutboxEntity> findByEventId(String eventId) {
        return jdbc.query("""
                SELECT *
                FROM %s
                WHERE event_id = ?
                """.formatted(TABLE), this::mapRow, eventId).stream().findFirst();
    }

    private Optional<OutboxEntity> findByLeaseToken(String leaseToken) {
        return jdbc.query("""
                SELECT *
                FROM %s
                WHERE lease_token = ?
                """.formatted(TABLE), this::mapRow, leaseToken).stream().findFirst();
    }

    private OutboxEntity mapRow(ResultSet row, int rowNumber) throws SQLException {
        return new OutboxEntity(
                row.getLong("id"),
                row.getString("event_id"),
                row.getString("aggregate_type"),
                row.getString("aggregate_id"),
                row.getString("event_type"),
                row.getInt("schema_version"),
                row.getString("topic"),
                row.getString("tag"),
                row.getString("message_key"),
                row.getString("payload"),
                row.getString("payload_hash"),
                instant(row, "deliver_at"),
                row.getString("status"),
                row.getInt("attempt_count"),
                instant(row, "next_attempt_at"),
                row.getString("lease_owner"),
                row.getString("lease_token"),
                nullableInstant(row, "lease_until"),
                nullableInstant(row, "published_at"),
                row.getString("last_error_code"),
                instant(row, "created_at"),
                instant(row, "updated_at"));
    }

    private Duration backoffFor(int completedAttempts) {
        Duration backoff = properties.initialBackoff();
        for (int attempt = 1; attempt < completedAttempts; attempt++) {
            if (backoff.compareTo(properties.maximumBackoff().dividedBy(2)) > 0) {
                return properties.maximumBackoff();
            }
            backoff = backoff.multipliedBy(2);
        }
        return backoff.compareTo(properties.maximumBackoff()) > 0
                ? properties.maximumBackoff()
                : backoff;
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(Objects.requireNonNull(value, "instant must not be null"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void requireText(String value, int maximumLength, String name) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maximumLength + " characters");
        }
    }

    public record NewOutboxEvent(
            String eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            int schemaVersion,
            String topic,
            String payload,
            String payloadHash,
            Instant deliverAt
    ) { }

    public record OutboxEntity(
            long id,
            String eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            int schemaVersion,
            String topic,
            String tag,
            String messageKey,
            String payload,
            String payloadHash,
            Instant deliverAt,
            String status,
            int attemptCount,
            Instant nextAttemptAt,
            String leaseOwner,
            String leaseToken,
            Instant leaseUntil,
            Instant publishedAt,
            String lastErrorCode,
            Instant createdAt,
            Instant updatedAt
    ) { }

    public enum FailureResult {
        RETRY_SCHEDULED,
        DEAD,
        STALE_LEASE
    }
}

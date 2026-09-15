package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Versioned envelope for messages exchanged between TideBid services.
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String producer,
        @JsonInclude(JsonInclude.Include.NON_NULL) String traceId,
        T payload
) {

    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    public EventEnvelope {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        eventType = requireText(eventType, "eventType");
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        producer = requireText(producer, "producer");
        if (traceId != null && !SAFE_TRACE_ID.matcher(traceId).matches()) {
            throw new IllegalArgumentException(
                    "traceId must contain 8 to 64 letters, digits, underscores, or hyphens"
            );
        }
        payload = Objects.requireNonNull(payload, "payload must not be null");
    }

    /**
     * Source-compatible constructor for producers that do not yet propagate a trace identifier.
     */
    public EventEnvelope(
            UUID eventId,
            String eventType,
            int schemaVersion,
            Instant occurredAt,
            String producer,
            T payload
    ) {
        this(eventId, eventType, schemaVersion, occurredAt, producer, null, payload);
    }

    public static <T> EventEnvelope<T> create(String eventType, String producer, T payload, Instant occurredAt) {
        return create(eventType, producer, null, payload, occurredAt);
    }

    public static <T> EventEnvelope<T> create(
            String eventType,
            String producer,
            String traceId,
            T payload,
            Instant occurredAt
    ) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, 1, occurredAt, producer, traceId, payload);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

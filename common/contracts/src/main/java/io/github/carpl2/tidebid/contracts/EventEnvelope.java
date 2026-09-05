package io.github.carpl2.tidebid.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned envelope for messages exchanged between TideBid services.
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String producer,
        T payload
) {

    public EventEnvelope {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        eventType = requireText(eventType, "eventType");
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        producer = requireText(producer, "producer");
        payload = Objects.requireNonNull(payload, "payload must not be null");
    }

    public static <T> EventEnvelope<T> create(String eventType, String producer, T payload, Instant occurredAt) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, 1, occurredAt, producer, payload);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

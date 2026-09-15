package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void roundTripsVersionedContract() throws Exception {
        EventEnvelope<UserIdentityContract> original = new EventEnvelope<>(
                UUID.fromString("4ad45898-3486-41cc-81d2-c240cf82aaae"),
                "account.user-created",
                1,
                Instant.parse("2026-01-01T00:00:00Z"),
                "tidebid-account",
                new UserIdentityContract(42L, "alice", Set.of("USER"))
        );

        String json = objectMapper.writeValueAsString(original);
        EventEnvelope<UserIdentityContract> restored = objectMapper.readValue(
                json,
                new TypeReference<>() {
                }
        );

        assertThat(restored).isEqualTo(original);
        assertThat(json).contains("account.user-created", "schemaVersion", "occurredAt");
        assertThat(json).doesNotContain("traceId");
    }

    @Test
    void roundTripsSafeTraceId() throws Exception {
        EventEnvelope<UserIdentityContract> original = new EventEnvelope<>(
                UUID.fromString("4ad45898-3486-41cc-81d2-c240cf82aaae"),
                "account.user-created",
                1,
                Instant.parse("2026-01-01T00:00:00Z"),
                "tidebid-account",
                "trace_20260915-abcdef",
                new UserIdentityContract(42L, "alice", Set.of("USER"))
        );

        String json = objectMapper.writeValueAsString(original);
        EventEnvelope<UserIdentityContract> restored = objectMapper.readValue(
                json,
                new TypeReference<>() {
                }
        );

        assertThat(restored).isEqualTo(original);
        assertThat(json).contains("\"traceId\":\"trace_20260915-abcdef\"");
    }

    @Test
    void deserializesLegacyJsonWithoutTraceId() throws Exception {
        String legacyJson = """
                {
                  "eventId": "4ad45898-3486-41cc-81d2-c240cf82aaae",
                  "eventType": "account.user-created",
                  "schemaVersion": 1,
                  "occurredAt": "2026-01-01T00:00:00Z",
                  "producer": "tidebid-account",
                  "payload": {
                    "userId": 42,
                    "username": "alice",
                    "roles": ["USER"]
                  }
                }
                """;

        EventEnvelope<UserIdentityContract> restored = objectMapper.readValue(
                legacyJson,
                new TypeReference<>() {
                }
        );

        assertThat(restored.traceId()).isNull();
        assertThat(restored.payload().userId()).isEqualTo(42L);
    }

    @Test
    void rejectsUnsafeOrOutOfBoundsTraceIds() {
        assertInvalidTraceId("short");
        assertInvalidTraceId("trace-with-newline\nforged");
        assertInvalidTraceId("a".repeat(65));
    }

    private static void assertInvalidTraceId(String traceId) {
        assertThatThrownBy(() -> new EventEnvelope<>(
                UUID.fromString("4ad45898-3486-41cc-81d2-c240cf82aaae"),
                "account.user-created",
                1,
                Instant.parse("2026-01-01T00:00:00Z"),
                "tidebid-account",
                traceId,
                new UserIdentityContract(42L, "alice", Set.of("USER"))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceId");
    }
}

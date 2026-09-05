package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
    }
}

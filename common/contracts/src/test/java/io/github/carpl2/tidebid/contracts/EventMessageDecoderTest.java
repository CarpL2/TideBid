package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static io.github.carpl2.tidebid.contracts.EventMessageDecoder.RejectionReason.MESSAGE_TOO_LARGE;
import static io.github.carpl2.tidebid.contracts.EventMessageDecoder.RejectionReason.MISSING_PAYLOAD;
import static io.github.carpl2.tidebid.contracts.EventMessageDecoder.RejectionReason.UNKNOWN_EVENT_TYPE;
import static io.github.carpl2.tidebid.contracts.EventMessageDecoder.RejectionReason.UNKNOWN_SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventMessageDecoderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final EventMessageDecoder decoder = new EventMessageDecoder(objectMapper);

    @Test
    void decodesRegisteredEventTypeAndVersion() throws Exception {
        EventEnvelope<CloseAuctionCommand> envelope = new EventEnvelope<>(
                UUID.fromString("4ad45898-3486-41cc-81d2-c240cf82aaae"),
                CloseAuctionCommand.EVENT_TYPE,
                CloseAuctionCommand.SCHEMA_VERSION,
                Instant.parse("2026-09-16T12:00:00Z"),
                "tidebid-auction",
                "trace_20260916-abcdef",
                new CloseAuctionCommand(9_007_199_254_740_993L, Instant.parse("2026-09-16T12:30:00Z"))
        );

        EventEnvelope<?> decoded = decoder.decode(objectMapper.writeValueAsBytes(envelope));

        assertThat(decoded).isEqualTo(envelope);
        assertThat(decoded.payload()).isInstanceOf(CloseAuctionCommand.class);
    }

    @Test
    void rejectsUnknownEventType() {
        assertRejected(
                envelopeJson("auction.not-registered", 1, "{\"auctionId\":\"42\"}"),
                UNKNOWN_EVENT_TYPE
        );
    }

    @Test
    void rejectsUnknownSchemaVersion() {
        assertRejected(
                envelopeJson(CloseAuctionCommand.EVENT_TYPE, 2, "{\"auctionId\":\"42\"}"),
                UNKNOWN_SCHEMA_VERSION
        );
    }

    @Test
    void rejectsMessageLargerThanConfiguredByteLimitBeforeJsonParsing() {
        EventMessageDecoder smallDecoder = new EventMessageDecoder(
                objectMapper,
                java.util.Map.of(CloseAuctionCommand.EVENT_TYPE, java.util.Map.of(1, CloseAuctionCommand.class)),
                32
        );
        byte[] oversizedInvalidJson = "x".repeat(33).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> smallDecoder.decode(oversizedInvalidJson))
                .isInstanceOfSatisfying(EventMessageDecoder.MessageRejectedException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(MESSAGE_TOO_LARGE)
                );
    }

    @Test
    void rejectsMissingPayload() {
        String json = """
                {
                  "eventId": "4ad45898-3486-41cc-81d2-c240cf82aaae",
                  "eventType": "auction.close",
                  "schemaVersion": 1,
                  "occurredAt": "2026-09-16T12:00:00Z",
                  "producer": "tidebid-auction"
                }
                """;

        assertRejected(json, MISSING_PAYLOAD);
    }

    private void assertRejected(String json, EventMessageDecoder.RejectionReason reason) {
        assertThatThrownBy(() -> decoder.decode(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(EventMessageDecoder.MessageRejectedException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(reason)
                );
    }

    private static String envelopeJson(String eventType, int schemaVersion, String payload) {
        return """
                {
                  "eventId": "4ad45898-3486-41cc-81d2-c240cf82aaae",
                  "eventType": "%s",
                  "schemaVersion": %d,
                  "occurredAt": "2026-09-16T12:00:00Z",
                  "producer": "tidebid-auction",
                  "payload": %s
                }
                """.formatted(eventType, schemaVersion, payload);
    }
}

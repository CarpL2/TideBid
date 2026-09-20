package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealtimeProtocolContractTest {

    private static final long LARGE_ID = 9_007_199_254_740_993L;
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00.123456Z");
    private static final UUID EVENT_ID = UUID.fromString("83d8e9b2-889f-48a4-9b31-685bde02962e");
    private static final Set<String> FORBIDDEN_PUBLIC_FIELDS = Set.of(
            "bidderid", "winnerid", "userid", "maxamount", "proxymax", "authorization",
            "token", "password", "secret", "accesskey", "signedurl", "objectkey"
    );

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void clientMessagesRoundTripWithStableTypesAndLargeIdsAsStrings() throws Exception {
        List<RealtimeClientMessage<?>> messages = List.of(
                client(RealtimeMessageType.SUBSCRIBE, new RealtimeSubscribe(LARGE_ID, 7)),
                client(RealtimeMessageType.UNSUBSCRIBE, new RealtimeUnsubscribe(LARGE_ID)),
                client(RealtimeMessageType.PING, new RealtimePing(NOW))
        );

        for (RealtimeClientMessage<?> message : messages) {
            String json = objectMapper.writeValueAsString(message);
            JavaType type = objectMapper.getTypeFactory().constructParametricType(
                    RealtimeClientMessage.class, message.payload().getClass());
            RealtimeClientMessage<?> restored = objectMapper.readValue(json, type);

            assertThat(restored).isEqualTo(message);
            assertThat(json).contains("\"protocolVersion\":1", "\"requestId\":\"request_12345678\"");
            if (message.payload() instanceof RealtimeSubscribe || message.payload() instanceof RealtimeUnsubscribe) {
                assertThat(objectMapper.readTree(json).at("/payload/auctionId").textValue())
                        .isEqualTo(Long.toString(LARGE_ID));
            }
        }
    }

    @Test
    void serverMessagesRoundTripWithMoneyUtcAndNoSensitiveIdentity() throws Exception {
        List<RealtimeServerMessage<?>> messages = serverMessages();

        for (RealtimeServerMessage<?> message : messages) {
            String json = objectMapper.writeValueAsString(message);
            JavaType type = objectMapper.getTypeFactory().constructParametricType(
                    RealtimeServerMessage.class, message.payload().getClass());
            RealtimeServerMessage<?> restored = objectMapper.readValue(json, type);
            String normalized = normalize(json);

            assertThat(restored).isEqualTo(message);
            assertThat(json).contains("\"protocolVersion\":1", NOW.toString());
            for (String forbidden : FORBIDDEN_PUBLIC_FIELDS) {
                assertThat(normalized).doesNotContain(forbidden);
            }
        }

        String bidJson = objectMapper.writeValueAsString(messages.stream()
                .filter(message -> message.type() == RealtimeMessageType.BID_ACCEPTED)
                .findFirst().orElseThrow());
        JsonNode bidPayload = objectMapper.readTree(bidJson).get("payload");
        assertThat(bidPayload.get("auctionId").textValue()).isEqualTo(Long.toString(LARGE_ID));
        assertThat(bidPayload.get("bidId").isTextual()).isTrue();
        assertThat(bidJson).contains("\"amount\":2333.00", "\"acceptedAt\":\"" + NOW + "\"");
    }

    @Test
    void decoderAcceptsOnlyKnownClientTypesVersionOneAndBoundedMessages() throws Exception {
        RealtimeClientMessageDecoder decoder = new RealtimeClientMessageDecoder(objectMapper);
        RealtimeClientMessage<RealtimeSubscribe> message = client(
                RealtimeMessageType.SUBSCRIBE, new RealtimeSubscribe(LARGE_ID, 7));

        assertThat(decoder.decode(objectMapper.writeValueAsBytes(message))).isEqualTo(message);

        assertRejected(decoder, """
                {"type":"UNKNOWN","protocolVersion":1,"requestId":"request_12345678","payload":{}}
                """, RealtimeClientMessageDecoder.RejectionReason.UNKNOWN_MESSAGE_TYPE);
        assertRejected(decoder, """
                {"type":"SUBSCRIBE","protocolVersion":2,"requestId":"request_12345678",
                 "payload":{"auctionId":"42","lastSequenceNo":0}}
                """, RealtimeClientMessageDecoder.RejectionReason.UNKNOWN_PROTOCOL_VERSION);
        assertRejected(decoder, """
                {"type":"SUBSCRIBE","protocolVersion":1,"requestId":"request_12345678"}
                """, RealtimeClientMessageDecoder.RejectionReason.MISSING_PAYLOAD);

        RealtimeClientMessageDecoder tinyDecoder = new RealtimeClientMessageDecoder(objectMapper, 16);
        assertThatThrownBy(() -> tinyDecoder.decode("x".repeat(17).getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(RealtimeClientMessageDecoder.MessageRejectedException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(RealtimeClientMessageDecoder.RejectionReason.MESSAGE_TOO_LARGE));
        assertThat(RealtimeProtocol.MAX_CLIENT_MESSAGE_BYTES).isEqualTo(8 * 1024);
    }

    @Test
    void eventAndRealtimeExtensionContractsRejectInvalidTimelines() {
        assertThatThrownBy(() -> new AuctionTimeExtendedEvent(LARGE_ID, NOW, NOW, 1, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("endAt");
        assertThatThrownBy(() -> new AuctionTimeExtendedEvent(
                LARGE_ID, NOW, NOW.plusSeconds(60), 0, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("extensionCount");
        assertThatThrownBy(() -> new RealtimeAuctionExtended(
                EVENT_ID, LARGE_ID, NOW, NOW.plusSeconds(60), 1, NOW.plusSeconds(61)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("extendedAt");
    }

    @Test
    void snapshotIsOrderedImmutableAndDoesNotExposeProxyMaximum() {
        RealtimeBidView first = new RealtimeBidView(LARGE_ID, money("100.00"), 1, true, NOW);
        RealtimeBidView second = new RealtimeBidView(LARGE_ID + 1, money("110.00"), 2, false, NOW.plusSeconds(1));
        List<RealtimeBidView> source = new java.util.ArrayList<>(List.of(first, second));
        RealtimeSnapshot snapshot = snapshot(source);

        source.clear();
        assertThat(snapshot.bids()).containsExactly(first, second);
        assertThatThrownBy(() -> snapshot.bids().add(first)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot(List.of(second, first)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ordered");
    }

    @Test
    void closeAndErrorCodesRemainExplicitAndSafe() {
        assertThat(RealtimeCloseCode.values()).extracting(RealtimeCloseCode::statusCode)
                .containsExactly(1008, 1009, 1011, 1012, 1013);
        assertThat(Stream.of(RealtimeCloseCode.values()).map(RealtimeCloseCode::safeReason))
                .allMatch(reason -> reason.length() <= 32)
                .noneMatch(reason -> FORBIDDEN_PUBLIC_FIELDS.stream().anyMatch(normalize(reason)::contains));
        assertThat(RealtimeErrorCode.values()).extracting(Enum::name)
                .contains("INVALID_MESSAGE", "UNSUPPORTED_PROTOCOL", "SNAPSHOT_UNAVAILABLE");
    }

    @Test
    void realtimeTopologyIsStableAndSubscribesOnlyToPublicAuctionEvents() {
        assertThat(RocketMqTopology.REALTIME_AUCTION_CONSUMER_GROUP)
                .isEqualTo("tidebid-realtime-auction-v1");
        assertThat(RocketMqTopology.REALTIME_AUCTION_EVENT_TAGS.split("\\|\\|"))
                .containsExactly(
                        BidAcceptedEvent.EVENT_TYPE,
                        AuctionTimeExtendedEvent.EVENT_TYPE,
                        AuctionClosedSoldEvent.EVENT_TYPE,
                        AuctionClosedUnsoldEvent.EVENT_TYPE
                );
        assertThat(RealtimeProtocol.AUCTION_CHANNEL_PREFIX).isEqualTo("tidebid:realtime:auction:");
    }

    private List<RealtimeServerMessage<?>> serverMessages() {
        return List.of(
                server(RealtimeMessageType.CONNECTED,
                        new RealtimeConnected("connection_12345678", NOW, RealtimeProtocol.HEARTBEAT_SECONDS)),
                server(RealtimeMessageType.SNAPSHOT, snapshot(List.of(
                        new RealtimeBidView(LARGE_ID + 1, money("2333.00"), 7, true, NOW)))),
                server(RealtimeMessageType.BID_ACCEPTED,
                        new RealtimeBidAccepted(EVENT_ID, LARGE_ID, LARGE_ID + 1,
                                money("2333.00"), 7, true, NOW)),
                server(RealtimeMessageType.AUCTION_EXTENDED,
                        new RealtimeAuctionExtended(EVENT_ID, LARGE_ID, NOW.plusSeconds(30),
                                NOW.plusSeconds(90), 1, NOW)),
                server(RealtimeMessageType.AUCTION_CLOSED,
                        new RealtimeAuctionClosed(EVENT_ID, LARGE_ID, RealtimeAuctionStatus.CLOSED_SOLD,
                                money("2333.00"), true, NOW)),
                server(RealtimeMessageType.PONG, new RealtimePong(NOW, NOW.minusSeconds(1))),
                server(RealtimeMessageType.RESYNC_REQUIRED,
                        new RealtimeResyncRequired(LARGE_ID, RealtimeResyncReason.HISTORY_GAP, 7)),
                server(RealtimeMessageType.ERROR,
                        new RealtimeError(RealtimeErrorCode.SNAPSHOT_UNAVAILABLE,
                                "snapshot temporarily unavailable", true))
        );
    }

    private RealtimeSnapshot snapshot(List<RealtimeBidView> bids) {
        return new RealtimeSnapshot(
                LARGE_ID,
                RealtimeAuctionStatus.OPEN,
                money("2333.00"),
                money("2343.00"),
                7,
                NOW.plusSeconds(60),
                null,
                1,
                7,
                true,
                true,
                bids
        );
    }

    private static <T> RealtimeClientMessage<T> client(RealtimeMessageType type, T payload) {
        return new RealtimeClientMessage<>(type, RealtimeProtocol.PROTOCOL_VERSION,
                "request_12345678", payload);
    }

    private static <T> RealtimeServerMessage<T> server(RealtimeMessageType type, T payload) {
        return new RealtimeServerMessage<>(type, RealtimeProtocol.PROTOCOL_VERSION,
                "request_12345678", NOW, payload);
    }

    private void assertRejected(
            RealtimeClientMessageDecoder decoder,
            String json,
            RealtimeClientMessageDecoder.RejectionReason reason
    ) {
        assertThatThrownBy(() -> decoder.decode(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(RealtimeClientMessageDecoder.MessageRejectedException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(reason));
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}

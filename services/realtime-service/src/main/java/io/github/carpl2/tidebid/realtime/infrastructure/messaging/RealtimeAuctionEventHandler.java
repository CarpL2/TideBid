package io.github.carpl2.tidebid.realtime.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.contracts.AuctionClosedSoldEvent;
import io.github.carpl2.tidebid.contracts.AuctionClosedUnsoldEvent;
import io.github.carpl2.tidebid.contracts.AuctionTimeExtendedEvent;
import io.github.carpl2.tidebid.contracts.BidAcceptedEvent;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.EventMessageDecoder;
import io.github.carpl2.tidebid.contracts.RocketMqTopology;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeProperties;
import io.github.carpl2.tidebid.realtime.infrastructure.fanout.RedisRealtimeEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

@Component
@Profile({"local-db", "nacos"})
public final class RealtimeAuctionEventHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeAuctionEventHandler.class);

    private static final Set<String> EVENT_TAGS = Set.of(
            BidAcceptedEvent.EVENT_TYPE,
            AuctionTimeExtendedEvent.EVENT_TYPE,
            AuctionClosedSoldEvent.EVENT_TYPE,
            AuctionClosedUnsoldEvent.EVENT_TYPE);

    private final RealtimeProperties properties;
    private final EventMessageDecoder decoder;
    private final RedisRealtimeEventPublisher publisher;

    public RealtimeAuctionEventHandler(RealtimeProperties properties, ObjectMapper objectMapper,
                                       RedisRealtimeEventPublisher publisher) {
        this.properties = properties;
        this.decoder = new EventMessageDecoder(objectMapper);
        this.publisher = publisher;
    }

    public void handle(RealtimeRocketMqTransport.RealtimeInboundMessage message) {
        EventEnvelope<?> envelope = decoder.decode(message.body());
        validateMetadata(message, envelope);
        long auctionId = auctionId(envelope.payload());
        boolean published = publisher.publish(envelope.eventId().toString(), auctionId,
                new String(message.body(), java.nio.charset.StandardCharsets.UTF_8));
        log.info("Realtime auction event handled eventId={} auctionId={} eventType={} outcome={}",
                envelope.eventId(), auctionId, envelope.eventType(), published ? "published" : "duplicate");
    }

    private void validateMetadata(RealtimeRocketMqTransport.RealtimeInboundMessage message,
                                  EventEnvelope<?> envelope) {
        if (!properties.rocketmq().auctionEventsTopic().equals(message.topic())
                || !EVENT_TAGS.contains(message.tag())
                || !message.keys().contains(envelope.eventId().toString())
                || !envelope.eventId().toString().equals(message.properties().get("eventId"))
                || !envelope.eventType().equals(message.properties().get("eventType"))
                || !Integer.toString(envelope.schemaVersion()).equals(message.properties().get("schemaVersion"))
                || !sha256(message.body()).equals(message.properties().get("payloadHash"))) {
            throw new IllegalArgumentException("RocketMQ metadata does not match event envelope");
        }
    }

    private static long auctionId(Object payload) {
        if (payload instanceof BidAcceptedEvent e) return e.auctionId();
        if (payload instanceof AuctionTimeExtendedEvent e) return e.auctionId();
        if (payload instanceof AuctionClosedSoldEvent e) return e.auctionId();
        if (payload instanceof AuctionClosedUnsoldEvent e) return e.auctionId();
        throw new IllegalArgumentException("unsupported auction event payload");
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}

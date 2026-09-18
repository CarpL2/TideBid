package io.github.carpl2.tidebid.trade.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.contracts.AuctionClosedSoldEvent;
import io.github.carpl2.tidebid.contracts.AuctionClosedUnsoldEvent;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.EventMessageDecoder;
import io.github.carpl2.tidebid.trade.application.TradeOrderCreationService;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeRocketMqProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

@Component
@Profile({"local-db", "nacos"})
public class TradeAuctionResultHandler implements TradeRocketMqTransport.InboundHandler {

    private static final Set<String> ACCEPTED_TYPES = Set.of(
            AuctionClosedSoldEvent.EVENT_TYPE,
            AuctionClosedUnsoldEvent.EVENT_TYPE);

    private final TradeRocketMqProperties properties;
    private final TradeOrderCreationService orders;
    private final JdbcTradeInboxRepository inbox;
    private final EventMessageDecoder decoder;
    private final Clock clock;

    public TradeAuctionResultHandler(
            TradeRocketMqProperties properties,
            TradeOrderCreationService orders,
            JdbcTradeInboxRepository inbox,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.properties = properties;
        this.orders = orders;
        this.inbox = inbox;
        this.decoder = new EventMessageDecoder(objectMapper);
        this.clock = clock;
    }

    @Override
    public TradeRocketMqTransport.ConsumerBinding binding() {
        return new TradeRocketMqTransport.ConsumerBinding(
                properties.consumerGroups().auctionResults(),
                Map.of(properties.topics().auctionEvents(),
                        AuctionClosedSoldEvent.EVENT_TYPE + "||" + AuctionClosedUnsoldEvent.EVENT_TYPE));
    }

    @Override
    @Transactional
    public void handle(TradeRocketMqTransport.InboundMessage message) {
        EventEnvelope<?> envelope = decoder.decode(message.body());
        if (!ACCEPTED_TYPES.contains(envelope.eventType())
                || (!(envelope.payload() instanceof AuctionClosedSoldEvent)
                && !(envelope.payload() instanceof AuctionClosedUnsoldEvent))) {
            throw new IllegalArgumentException("message is not an auction close result");
        }
        validateTransportMetadata(message, envelope);
        JdbcTradeInboxRepository.InboxDecision decision = inbox.recordProcessed(
                new JdbcTradeInboxRepository.InboxEntity(
                        properties.consumerGroups().auctionResults(),
                        envelope.eventId().toString(),
                        envelope.eventType(),
                        envelope.schemaVersion(),
                        sha256(message.body()),
                        clock.instant()));
        if (decision == JdbcTradeInboxRepository.InboxDecision.DUPLICATE) {
            return;
        }
        if (envelope.payload() instanceof AuctionClosedSoldEvent sold) {
            orders.createFrom(sold, envelope.traceId());
        }
        // An unsold result is deliberately represented only by the committed Inbox row.
    }

    private void validateTransportMetadata(
            TradeRocketMqTransport.InboundMessage message,
            EventEnvelope<?> envelope
    ) {
        if (!properties.topics().auctionEvents().equals(message.topic())
                || !envelope.eventType().equals(message.tag())
                || !message.keys().contains(envelope.eventId().toString())
                || !envelope.eventId().toString().equals(message.properties().get("eventId"))
                || !envelope.eventType().equals(message.properties().get("eventType"))
                || !Integer.toString(envelope.schemaVersion()).equals(message.properties().get("schemaVersion"))
                || !sha256(message.body()).equals(message.properties().get("payloadHash"))) {
            throw new IllegalArgumentException("RocketMQ metadata does not match the event envelope");
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}

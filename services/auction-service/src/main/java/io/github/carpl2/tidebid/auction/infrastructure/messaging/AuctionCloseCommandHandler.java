package io.github.carpl2.tidebid.auction.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.auction.application.AuctionClosingService;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionRocketMqProperties;
import io.github.carpl2.tidebid.contracts.CloseAuctionCommand;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.EventMessageDecoder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;

@Component
@Profile({"local-db", "nacos"})
public class AuctionCloseCommandHandler implements AuctionRocketMqTransport.InboundHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuctionCloseCommandHandler.class);

    private final AuctionRocketMqProperties properties;
    private final AuctionClosingService closingService;
    private final JdbcAuctionInboxRepository inbox;
    private final EventMessageDecoder decoder;
    private final Clock clock;

    public AuctionCloseCommandHandler(
            AuctionRocketMqProperties properties,
            AuctionClosingService closingService,
            JdbcAuctionInboxRepository inbox,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.properties = properties;
        this.closingService = closingService;
        this.inbox = inbox;
        this.decoder = new EventMessageDecoder(objectMapper);
        this.clock = clock;
    }

    @Override
    public AuctionRocketMqTransport.ConsumerBinding binding() {
        return new AuctionRocketMqTransport.ConsumerBinding(
                properties.consumerGroups().closeAuction(),
                Map.of(properties.topics().scheduledCommands(), CloseAuctionCommand.EVENT_TYPE));
    }

    @Override
    @Transactional
    public void handle(AuctionRocketMqTransport.InboundMessage message) {
        EventEnvelope<?> decoded = decoder.decode(message.body());
        if (!(decoded.payload() instanceof CloseAuctionCommand command)) {
            throw new IllegalArgumentException("message is not a close-auction command");
        }
        validateTransportMetadata(message, decoded);
        String payloadHash = sha256(message.body());
        JdbcAuctionInboxRepository.InboxDecision decision = inbox.recordProcessed(
                new JdbcAuctionInboxRepository.InboxEntity(
                        properties.consumerGroups().closeAuction(),
                        decoded.eventId().toString(),
                        decoded.eventType(),
                        decoded.schemaVersion(),
                        payloadHash,
                        clock.instant()));
        if (decision == JdbcAuctionInboxRepository.InboxDecision.DUPLICATE) {
            return;
        }
        var result = closingService.fromMessage(
                command.auctionId(), command.expectedEndAt(), decoded.eventId(), decoded.traceId());
        LOGGER.info("Close command processed: eventId={}, auctionId={}, result={}",
                decoded.eventId(), command.auctionId(), result);
    }

    private void validateTransportMetadata(
            AuctionRocketMqTransport.InboundMessage message,
            EventEnvelope<?> envelope
    ) {
        if (!properties.topics().scheduledCommands().equals(message.topic())
                || !CloseAuctionCommand.EVENT_TYPE.equals(message.tag())
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

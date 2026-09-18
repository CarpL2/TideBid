package io.github.carpl2.tidebid.trade.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.EventMessageDecoder;
import io.github.carpl2.tidebid.contracts.OrderPaymentTimeoutCommand;
import io.github.carpl2.tidebid.trade.application.TradePaymentTimeoutService;
import io.github.carpl2.tidebid.trade.application.TradePaymentTimeoutTransaction;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeRocketMqProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

@Component
@Profile({"local-db", "nacos"})
public class TradePaymentTimeoutCommandHandler implements TradeRocketMqTransport.InboundHandler {

    private final TradeRocketMqProperties properties;
    private final TradePaymentTimeoutService timeouts;
    private final EventMessageDecoder decoder;

    public TradePaymentTimeoutCommandHandler(
            TradeRocketMqProperties properties,
            TradePaymentTimeoutService timeouts,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.timeouts = timeouts;
        this.decoder = new EventMessageDecoder(objectMapper);
    }

    @Override
    public TradeRocketMqTransport.ConsumerBinding binding() {
        return new TradeRocketMqTransport.ConsumerBinding(
                properties.consumerGroups().paymentTimeout(),
                Map.of(properties.topics().scheduledCommands(), OrderPaymentTimeoutCommand.EVENT_TYPE));
    }

    @Override
    public void handle(TradeRocketMqTransport.InboundMessage message) {
        EventEnvelope<?> envelope = decoder.decode(message.body());
        if (!(envelope.payload() instanceof OrderPaymentTimeoutCommand command)) {
            throw new IllegalArgumentException("message is not an order payment timeout command");
        }
        validateTransportMetadata(message, envelope);
        timeouts.fromMessage(new TradePaymentTimeoutTransaction.MessageCommand(
                properties.consumerGroups().paymentTimeout(), envelope.eventId(), envelope.eventType(),
                envelope.schemaVersion(), sha256(message.body()), command.orderId(),
                command.expectedPaymentDeadline(), envelope.traceId()));
    }

    private void validateTransportMetadata(
            TradeRocketMqTransport.InboundMessage message,
            EventEnvelope<?> envelope
    ) {
        if (!properties.topics().scheduledCommands().equals(message.topic())
                || !OrderPaymentTimeoutCommand.EVENT_TYPE.equals(message.tag())
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

package io.github.carpl2.tidebid.trade.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.EventMessageDecoder;
import io.github.carpl2.tidebid.contracts.DepositSettlementType;
import io.github.carpl2.tidebid.contracts.SellerCreditedEvent;
import io.github.carpl2.tidebid.contracts.WalletHoldSettledEvent;
import io.github.carpl2.tidebid.trade.application.TradeDepositSettlementService;
import io.github.carpl2.tidebid.trade.application.TradeSellerSettlementService;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeRocketMqProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;

@Component
@Profile({"local-db", "nacos"})
public class TradeAccountResultHandler implements TradeRocketMqTransport.InboundHandler {

    private final TradeRocketMqProperties properties;
    private final TradeDepositSettlementService settlements;
    private final TradeSellerSettlementService sellerSettlements;
    private final JdbcTradeInboxRepository inbox;
    private final EventMessageDecoder decoder;
    private final Clock clock;

    public TradeAccountResultHandler(
            TradeRocketMqProperties properties,
            TradeDepositSettlementService settlements,
            TradeSellerSettlementService sellerSettlements,
            JdbcTradeInboxRepository inbox,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.properties = properties;
        this.settlements = settlements;
        this.sellerSettlements = sellerSettlements;
        this.inbox = inbox;
        this.decoder = new EventMessageDecoder(objectMapper);
        this.clock = clock;
    }

    @Override
    public TradeRocketMqTransport.ConsumerBinding binding() {
        return new TradeRocketMqTransport.ConsumerBinding(
                properties.consumerGroups().accountResults(),
                Map.of(properties.topics().accountEvents(),
                        WalletHoldSettledEvent.EVENT_TYPE + "||" + SellerCreditedEvent.EVENT_TYPE));
    }

    @Override
    @Transactional
    public void handle(TradeRocketMqTransport.InboundMessage message) {
        EventEnvelope<?> envelope = decoder.decode(message.body());
        if (!(envelope.payload() instanceof WalletHoldSettledEvent)
                && !(envelope.payload() instanceof SellerCreditedEvent)) {
            throw new IllegalArgumentException("message is not a supported account result");
        }
        validateTransportMetadata(message, envelope);
        JdbcTradeInboxRepository.InboxDecision decision = inbox.recordProcessed(
                new JdbcTradeInboxRepository.InboxEntity(
                        properties.consumerGroups().accountResults(), envelope.eventId().toString(),
                        envelope.eventType(), envelope.schemaVersion(), sha256(message.body()), clock.instant()));
        if (decision == JdbcTradeInboxRepository.InboxDecision.DUPLICATE) {
            return;
        }
        if (envelope.payload() instanceof WalletHoldSettledEvent result) {
            // Account publishes both winner captures and loser/unsold releases on the same topic.
            // A release has no order and is therefore intentionally irrelevant to Trade, but it
            // is still recorded in the Inbox so a redelivery remains an idempotent success.
            if (result.settlementType() == DepositSettlementType.RELEASE) {
                return;
            }
            settlements.apply(result, envelope.traceId());
        } else {
            sellerSettlements.apply((SellerCreditedEvent) envelope.payload());
        }
    }

    private void validateTransportMetadata(
            TradeRocketMqTransport.InboundMessage message, EventEnvelope<?> envelope
    ) {
        if (!properties.topics().accountEvents().equals(message.topic())
                || (!WalletHoldSettledEvent.EVENT_TYPE.equals(message.tag())
                    && !SellerCreditedEvent.EVENT_TYPE.equals(message.tag()))
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

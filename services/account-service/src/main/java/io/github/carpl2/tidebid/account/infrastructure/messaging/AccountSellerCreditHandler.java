package io.github.carpl2.tidebid.account.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.account.application.SellerCreditService;
import io.github.carpl2.tidebid.account.infrastructure.config.AccountRocketMqProperties;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.EventMessageDecoder;
import io.github.carpl2.tidebid.contracts.SellerCreditRequestedEvent;
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
public class AccountSellerCreditHandler implements AccountRocketMqTransport.InboundHandler {

    private final AccountRocketMqProperties properties;
    private final SellerCreditService service;
    private final JdbcAccountInboxRepository inbox;
    private final EventMessageDecoder decoder;
    private final Clock clock;

    public AccountSellerCreditHandler(
            AccountRocketMqProperties properties,
            SellerCreditService service,
            JdbcAccountInboxRepository inbox,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.properties = properties;
        this.service = service;
        this.inbox = inbox;
        this.decoder = new EventMessageDecoder(objectMapper);
        this.clock = clock;
    }

    @Override
    public AccountRocketMqTransport.ConsumerBinding binding() {
        return new AccountRocketMqTransport.ConsumerBinding(
                properties.consumerGroups().sellerCredit(),
                Map.of(properties.topics().tradeEvents(), SellerCreditRequestedEvent.EVENT_TYPE));
    }

    @Override
    @Transactional
    public void handle(AccountRocketMqTransport.InboundMessage message) {
        EventEnvelope<?> envelope = decoder.decode(message.body());
        if (!(envelope.payload() instanceof SellerCreditRequestedEvent request)) {
            throw new IllegalArgumentException("message is not a seller-credit request");
        }
        validateTransportMetadata(message, envelope);
        JdbcAccountInboxRepository.InboxDecision decision = inbox.recordProcessed(
                new JdbcAccountInboxRepository.InboxEntity(
                        properties.consumerGroups().sellerCredit(), envelope.eventId().toString(),
                        envelope.eventType(), envelope.schemaVersion(), sha256(message.body()), clock.instant()));
        if (decision == JdbcAccountInboxRepository.InboxDecision.DUPLICATE) {
            return;
        }
        service.credit(envelope.eventId(), envelope.traceId(), request);
    }

    private void validateTransportMetadata(
            AccountRocketMqTransport.InboundMessage message,
            EventEnvelope<?> envelope
    ) {
        if (!properties.topics().tradeEvents().equals(message.topic())
                || !SellerCreditRequestedEvent.EVENT_TYPE.equals(message.tag())
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

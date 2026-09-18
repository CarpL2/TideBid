package io.github.carpl2.tidebid.trade.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.contracts.AuctionClosedSoldEvent;
import io.github.carpl2.tidebid.contracts.DepositSettlementRequestedEvent;
import io.github.carpl2.tidebid.contracts.DepositSettlementType;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.OrderPaidEvent;
import io.github.carpl2.tidebid.contracts.OrderPaymentTimeoutCommand;
import io.github.carpl2.tidebid.contracts.OrderPaymentTimedOutEvent;
import io.github.carpl2.tidebid.contracts.RocketMqTopology;
import io.github.carpl2.tidebid.contracts.SellerCreditRequestedEvent;
import io.github.carpl2.tidebid.trade.domain.TradeOrder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Component
@Profile({"local-db", "nacos"})
public class TradeOutboxEventFactory {

    private static final String PRODUCER = "tidebid-trade-service";
    private final ObjectMapper objectMapper;

    public TradeOutboxEventFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JdbcTradeOutboxRepository.NewOutboxEvent captureWinnerDeposit(
            TradeOrder order,
            AuctionClosedSoldEvent source,
            String traceId,
            Instant occurredAt
    ) {
        DepositSettlementRequestedEvent payload = new DepositSettlementRequestedEvent(
                DepositSettlementType.CAPTURE,
                source.auctionId(),
                order.id(),
                source.winnerId(),
                source.winnerHoldNo(),
                source.depositAmount(),
                source.finalPrice());
        UUID eventId = deterministicCaptureEventId(source.auctionId(), source.winnerHoldNo());
        EventEnvelope<DepositSettlementRequestedEvent> envelope = new EventEnvelope<>(
                eventId,
                DepositSettlementRequestedEvent.EVENT_TYPE,
                DepositSettlementRequestedEvent.SCHEMA_VERSION,
                occurredAt,
                PRODUCER,
                traceId,
                payload);
        try {
            String json = objectMapper.writeValueAsString(envelope);
            return new JdbcTradeOutboxRepository.NewOutboxEvent(
                    eventId.toString(),
                    "TRADE_ORDER",
                    Long.toString(order.id()),
                    envelope.eventType(),
                    envelope.schemaVersion(),
                    RocketMqTopology.TRADE_EVENTS_TOPIC,
                    json,
                    sha256(json),
                    occurredAt);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Winner deposit request could not be encoded", exception);
        }
    }

    public static UUID deterministicCaptureEventId(long auctionId, String holdNo) {
        return UUID.nameUUIDFromBytes(("tidebid:deposit-capture:v1:" + auctionId + ":" + holdNo)
                .getBytes(StandardCharsets.UTF_8));
    }

    public JdbcTradeOutboxRepository.NewOutboxEvent paymentTimeout(
            OrderPaymentTimeoutCommand payload, String traceId, Instant occurredAt
    ) {
        return event(
                deterministicEventId("payment-timeout", payload.orderId()),
                payload.orderId(), payload, OrderPaymentTimeoutCommand.EVENT_TYPE,
                OrderPaymentTimeoutCommand.SCHEMA_VERSION, RocketMqTopology.SCHEDULED_COMMANDS_TOPIC,
                traceId, occurredAt, payload.expectedPaymentDeadline());
    }

    public JdbcTradeOutboxRepository.NewOutboxEvent orderPaid(
            OrderPaidEvent payload, String traceId, Instant occurredAt
    ) {
        return event(
                deterministicEventId("order-paid", payload.orderId()),
                payload.orderId(), payload, OrderPaidEvent.EVENT_TYPE, OrderPaidEvent.SCHEMA_VERSION,
                RocketMqTopology.TRADE_EVENTS_TOPIC, traceId, occurredAt, occurredAt);
    }

    public JdbcTradeOutboxRepository.NewOutboxEvent orderPaymentTimedOut(
            OrderPaymentTimedOutEvent payload, String traceId, Instant occurredAt
    ) {
        return event(
                deterministicEventId("order-payment-timed-out", payload.orderId()),
                payload.orderId(), payload, OrderPaymentTimedOutEvent.EVENT_TYPE,
                OrderPaymentTimedOutEvent.SCHEMA_VERSION, RocketMqTopology.TRADE_EVENTS_TOPIC,
                traceId, occurredAt, occurredAt);
    }

    public JdbcTradeOutboxRepository.NewOutboxEvent sellerCreditRequested(
            SellerCreditRequestedEvent payload, String traceId, Instant occurredAt
    ) {
        return event(
                deterministicEventId("seller-credit", payload.orderId()),
                payload.orderId(), payload, SellerCreditRequestedEvent.EVENT_TYPE,
                SellerCreditRequestedEvent.SCHEMA_VERSION, RocketMqTopology.TRADE_EVENTS_TOPIC,
                traceId, occurredAt, occurredAt);
    }

    public static UUID deterministicEventId(String purpose, long orderId) {
        return UUID.nameUUIDFromBytes(("tidebid:" + purpose + ":v1:" + orderId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private JdbcTradeOutboxRepository.NewOutboxEvent event(
            UUID eventId,
            long orderId,
            Object payload,
            String eventType,
            int schemaVersion,
            String topic,
            String traceId,
            Instant occurredAt,
            Instant deliverAt
    ) {
        EventEnvelope<Object> envelope = new EventEnvelope<>(
                eventId, eventType, schemaVersion, occurredAt, PRODUCER, traceId, payload);
        try {
            String json = objectMapper.writeValueAsString(envelope);
            return new JdbcTradeOutboxRepository.NewOutboxEvent(
                    eventId.toString(), "TRADE_ORDER", Long.toString(orderId), eventType,
                    schemaVersion, topic, json, sha256(json), deliverAt);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(eventType + " could not be encoded", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}

package io.github.carpl2.tidebid.auction.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.contracts.BidAcceptedEvent;
import io.github.carpl2.tidebid.contracts.AuctionClosedSoldEvent;
import io.github.carpl2.tidebid.contracts.AuctionClosedUnsoldEvent;
import io.github.carpl2.tidebid.contracts.CloseAuctionCommand;
import io.github.carpl2.tidebid.contracts.DepositSettlementRequestedEvent;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.RocketMqTopology;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Creates the immutable wire representation before it enters the transactional outbox. */
@Component
@Profile({"local-db", "nacos"})
public class AuctionOutboxEventFactory {

    private static final String PRODUCER = "tidebid-auction-service";
    private static final String AGGREGATE_TYPE = "AUCTION";

    private final ObjectMapper objectMapper;

    public AuctionOutboxEventFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JdbcAuctionOutboxRepository.NewOutboxEvent bidAccepted(BidRecord bid) {
        BidAcceptedEvent payload = new BidAcceptedEvent(
                bid.auctionId(), bid.id(), bid.bidderId(), bid.amount(), bid.sequenceNo(), bid.createdAt());
        EventEnvelope<BidAcceptedEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                BidAcceptedEvent.EVENT_TYPE,
                BidAcceptedEvent.SCHEMA_VERSION,
                bid.createdAt(),
                PRODUCER,
                bid.requestId(),
                payload);
        return encode(
                envelope,
                Long.toString(bid.auctionId()),
                RocketMqTopology.AUCTION_EVENTS_TOPIC,
                bid.createdAt());
    }

    public JdbcAuctionOutboxRepository.NewOutboxEvent closeAuction(AuctionSession session, Instant scheduledAt) {
        return closeAuction(session.id(), session.endAt(), scheduledAt);
    }

    public JdbcAuctionOutboxRepository.NewOutboxEvent closeAuction(
            long auctionId,
            Instant expectedEndAt,
            Instant scheduledAt
    ) {
        UUID eventId = deterministicCloseEventId(auctionId, expectedEndAt);
        CloseAuctionCommand payload = new CloseAuctionCommand(auctionId, expectedEndAt);
        EventEnvelope<CloseAuctionCommand> envelope = new EventEnvelope<>(
                eventId,
                CloseAuctionCommand.EVENT_TYPE,
                CloseAuctionCommand.SCHEMA_VERSION,
                scheduledAt,
                PRODUCER,
                payload);
        return encode(
                envelope,
                Long.toString(auctionId),
                RocketMqTopology.SCHEDULED_COMMANDS_TOPIC,
                expectedEndAt);
    }

    public static UUID deterministicCloseEventId(long auctionId, Instant expectedEndAt) {
        String key = "tidebid:auction-close:v1:" + auctionId + ":" + expectedEndAt;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public JdbcAuctionOutboxRepository.NewOutboxEvent closeAuctionRetry(
            long auctionId,
            Instant expectedEndAt,
            Instant scheduledAt,
            UUID sourceEventId
    ) {
        UUID eventId = UUID.nameUUIDFromBytes(("tidebid:auction-close-retry:v1:" + sourceEventId + ":"
                + expectedEndAt).getBytes(StandardCharsets.UTF_8));
        return encode(new EventEnvelope<>(
                        eventId,
                        CloseAuctionCommand.EVENT_TYPE,
                        CloseAuctionCommand.SCHEMA_VERSION,
                        scheduledAt,
                        PRODUCER,
                        new CloseAuctionCommand(auctionId, expectedEndAt)),
                Long.toString(auctionId), RocketMqTopology.SCHEDULED_COMMANDS_TOPIC, expectedEndAt);
    }

    public JdbcAuctionOutboxRepository.NewOutboxEvent auctionClosedSold(
            AuctionClosedSoldEvent payload,
            Instant occurredAt,
            String traceId
    ) {
        return businessEvent(UUID.randomUUID(), payload.auctionId(), payload, AuctionClosedSoldEvent.EVENT_TYPE,
                AuctionClosedSoldEvent.SCHEMA_VERSION, occurredAt, traceId);
    }

    public JdbcAuctionOutboxRepository.NewOutboxEvent auctionClosedUnsold(
            AuctionClosedUnsoldEvent payload,
            Instant occurredAt,
            String traceId
    ) {
        return businessEvent(UUID.randomUUID(), payload.auctionId(), payload, AuctionClosedUnsoldEvent.EVENT_TYPE,
                AuctionClosedUnsoldEvent.SCHEMA_VERSION, occurredAt, traceId);
    }

    public JdbcAuctionOutboxRepository.NewOutboxEvent depositRelease(
            DepositSettlementRequestedEvent payload,
            Instant occurredAt,
            String traceId
    ) {
        UUID eventId = UUID.nameUUIDFromBytes(("tidebid:deposit-release:v1:" + payload.auctionId() + ":"
                + payload.holdNo()).getBytes(StandardCharsets.UTF_8));
        return businessEvent(eventId, payload.auctionId(), payload, DepositSettlementRequestedEvent.EVENT_TYPE,
                DepositSettlementRequestedEvent.SCHEMA_VERSION, occurredAt, traceId);
    }

    private JdbcAuctionOutboxRepository.NewOutboxEvent businessEvent(
            UUID eventId,
            long auctionId,
            Object payload,
            String eventType,
            int schemaVersion,
            Instant occurredAt,
            String traceId
    ) {
        return encode(new EventEnvelope<>(eventId, eventType, schemaVersion, occurredAt, PRODUCER, traceId, payload),
                Long.toString(auctionId), RocketMqTopology.AUCTION_EVENTS_TOPIC, occurredAt);
    }

    private JdbcAuctionOutboxRepository.NewOutboxEvent encode(
            EventEnvelope<?> envelope,
            String aggregateId,
            String topic,
            Instant deliverAt
    ) {
        try {
            String json = objectMapper.writeValueAsString(envelope);
            return new JdbcAuctionOutboxRepository.NewOutboxEvent(
                    envelope.eventId().toString(),
                    AGGREGATE_TYPE,
                    aggregateId,
                    envelope.eventType(),
                    envelope.schemaVersion(),
                    topic,
                    json,
                    sha256(json),
                    deliverAt);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Auction event envelope could not be encoded", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}

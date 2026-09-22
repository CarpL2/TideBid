package io.github.carpl2.tidebid.realtime.infrastructure.fanout;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.contracts.AuctionClosedSoldEvent;
import io.github.carpl2.tidebid.contracts.AuctionClosedUnsoldEvent;
import io.github.carpl2.tidebid.contracts.AuctionTimeExtendedEvent;
import io.github.carpl2.tidebid.contracts.BidAcceptedEvent;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.RealtimeAuctionClosed;
import io.github.carpl2.tidebid.contracts.RealtimeAuctionExtended;
import io.github.carpl2.tidebid.contracts.RealtimeAuctionStatus;
import io.github.carpl2.tidebid.contracts.RealtimeBidAccepted;
import io.github.carpl2.tidebid.contracts.RealtimeMessageType;
import io.github.carpl2.tidebid.contracts.RealtimeServerMessage;
import io.github.carpl2.tidebid.realtime.infrastructure.websocket.RealtimeWebSocketAttributes;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RealtimeWebSocketSessionRegistry {

    private final ObjectMapper objectMapper;
    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public RealtimeWebSocketSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void subscribe(long auctionId, WebSocketSession session) {
        sessions.computeIfAbsent(auctionId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unsubscribe(long auctionId, WebSocketSession session) {
        Set<WebSocketSession> values = sessions.get(auctionId);
        if (values != null) {
            values.remove(session);
            if (values.isEmpty()) sessions.remove(auctionId, values);
        }
    }

    public void remove(WebSocketSession session) {
        sessions.values().forEach(values -> values.remove(session));
        sessions.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public void broadcast(EventEnvelope<?> envelope) {
        long auctionId = auctionId(envelope.payload());
        Set<WebSocketSession> values = sessions.getOrDefault(auctionId, Set.of());
        for (WebSocketSession session : values) {
            if (!session.isOpen()) {
                remove(session);
                continue;
            }
            Object message = toMessage(envelope, session);
            if (message != null) send(session, (RealtimeServerMessage<?>) message);
        }
    }

    private Object toMessage(EventEnvelope<?> envelope, WebSocketSession session) {
        long userId = ((Number) session.getAttributes().getOrDefault(
                RealtimeWebSocketAttributes.USER_ID, 0L)).longValue();
        Object payload = envelope.payload();
        if (payload instanceof BidAcceptedEvent event) {
            return server(RealtimeMessageType.BID_ACCEPTED, envelope.eventId().toString(),
                    new RealtimeBidAccepted(envelope.eventId(), event.auctionId(), event.bidId(), event.amount(),
                            event.sequenceNo(), event.bidderId() == userId, event.acceptedAt()));
        }
        if (payload instanceof AuctionTimeExtendedEvent event) {
            return server(RealtimeMessageType.AUCTION_EXTENDED, envelope.eventId().toString(),
                    new RealtimeAuctionExtended(envelope.eventId(), event.auctionId(), event.previousEndAt(),
                            event.endAt(), event.extensionCount(), event.extendedAt()));
        }
        if (payload instanceof AuctionClosedSoldEvent event) {
            return server(RealtimeMessageType.AUCTION_CLOSED, envelope.eventId().toString(),
                    new RealtimeAuctionClosed(envelope.eventId(), event.auctionId(), RealtimeAuctionStatus.CLOSED_SOLD,
                            event.finalPrice(), event.winnerId() == userId, event.closedAt()));
        }
        if (payload instanceof AuctionClosedUnsoldEvent event) {
            return server(RealtimeMessageType.AUCTION_CLOSED, envelope.eventId().toString(),
                    new RealtimeAuctionClosed(envelope.eventId(), event.auctionId(), RealtimeAuctionStatus.CLOSED_UNSOLD,
                            null, false, event.closedAt()));
        }
        return null;
    }

    private RealtimeServerMessage<?> server(RealtimeMessageType type, String requestId, Object payload) {
        return new RealtimeServerMessage<>(type, 1, requestId, Instant.now(), payload);
    }

    private void send(WebSocketSession session, RealtimeServerMessage<?> message) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
            }
        } catch (Exception ignored) {
            // A disconnected or slow session is removed by the next broadcast/close callback.
        }
    }

    private static long auctionId(Object payload) {
        if (payload instanceof BidAcceptedEvent event) return event.auctionId();
        if (payload instanceof AuctionTimeExtendedEvent event) return event.auctionId();
        if (payload instanceof AuctionClosedSoldEvent event) return event.auctionId();
        if (payload instanceof AuctionClosedUnsoldEvent event) return event.auctionId();
        throw new IllegalArgumentException("unsupported realtime event payload");
    }
}

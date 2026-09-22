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
import io.github.carpl2.tidebid.contracts.RealtimeSnapshot;
import io.github.carpl2.tidebid.contracts.RealtimeBidView;
import io.github.carpl2.tidebid.realtime.infrastructure.websocket.RealtimeWebSocketAttributes;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

public final class RealtimeWebSocketSessionRegistry {

    private final ObjectMapper objectMapper;
    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, Map<Long, SyncBuffer>> syncing = new ConcurrentHashMap<>();

    public RealtimeWebSocketSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void subscribe(long auctionId, WebSocketSession session) {
        sessions.computeIfAbsent(auctionId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void beginSync(long auctionId, WebSocketSession session, int capacity) {
        syncing.computeIfAbsent(session, ignored -> new ConcurrentHashMap<>())
                .put(auctionId, new SyncBuffer(capacity));
    }

    public boolean sendSnapshot(long auctionId, WebSocketSession session, RealtimeSnapshot snapshot,
                                String requestId) {
        SyncBuffer buffer = syncing.getOrDefault(session, Map.of()).get(auctionId);
        if (buffer == null) return false;
        try {
            synchronized (buffer) {
                send(session, server(RealtimeMessageType.SNAPSHOT, requestId, snapshot));
                if (buffer.overflowed) return false;
                Set<Long> replayedSequences = new HashSet<>();
                for (RealtimeBidView bid : snapshot.bids()) replayedSequences.add(bid.sequenceNo());
                Set<java.util.UUID> replayedEvents = new HashSet<>();
                for (EventEnvelope<?> event : buffer.events) {
                    if (!replayedEvents.add(event.eventId())) continue;
                    if (event.payload() instanceof BidAcceptedEvent bid
                            && (bid.sequenceNo() <= snapshot.lastSequenceNo()
                            || !replayedSequences.add(bid.sequenceNo()))) continue;
                    Object message = toMessage(event, session);
                    if (message != null) send(session, (RealtimeServerMessage<?>) message);
                }
                buffer.live = true;
                Map<Long, SyncBuffer> values = syncing.get(session);
                if (values != null) {
                    values.remove(auctionId, buffer);
                    if (values.isEmpty()) syncing.remove(session, values);
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public void cancelSync(long auctionId, WebSocketSession session) {
        Map<Long, SyncBuffer> values = syncing.get(session);
        if (values != null) values.remove(auctionId);
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
        syncing.remove(session);
    }

    public void broadcast(EventEnvelope<?> envelope) {
        long auctionId = auctionId(envelope.payload());
        Set<WebSocketSession> values = sessions.getOrDefault(auctionId, Set.of());
        for (WebSocketSession session : values) {
            if (!session.isOpen()) {
                remove(session);
                continue;
            }
            SyncBuffer buffer = syncing.getOrDefault(session, Map.of()).get(auctionId);
            if (buffer != null) {
                synchronized (buffer) {
                    if (!buffer.live) {
                        buffer.add(envelope);
                        continue;
                    }
                }
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

    private static final class SyncBuffer {
        private final int capacity;
        private final List<EventEnvelope<?>> events = new ArrayList<>();
        private boolean overflowed;
        private boolean live;

        private SyncBuffer(int capacity) { this.capacity = capacity; }
        private synchronized void add(EventEnvelope<?> event) {
            if (events.size() >= capacity) overflowed = true;
            else events.add(event);
        }
    }
}

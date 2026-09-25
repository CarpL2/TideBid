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
import io.github.carpl2.tidebid.contracts.RealtimeResyncReason;
import io.github.carpl2.tidebid.contracts.RealtimeResyncRequired;
import io.github.carpl2.tidebid.contracts.RealtimeServerMessage;
import io.github.carpl2.tidebid.contracts.RealtimeSnapshot;
import io.github.carpl2.tidebid.contracts.RealtimeBidView;
import io.github.carpl2.tidebid.realtime.infrastructure.websocket.RealtimeWebSocketAttributes;
import io.github.carpl2.tidebid.realtime.infrastructure.websocket.RealtimeWebSocketSendQueue;
import io.github.carpl2.tidebid.realtime.infrastructure.metrics.RealtimeMetrics;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import org.springframework.web.socket.CloseStatus;

public final class RealtimeWebSocketSessionRegistry {

    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final int sendCapacity;
    private final RealtimeMetrics metrics;
    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, Map<Long, SyncBuffer>> syncing = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, RealtimeWebSocketSendQueue> sendQueues = new ConcurrentHashMap<>();

    public RealtimeWebSocketSessionRegistry(ObjectMapper objectMapper) {
        this(objectMapper, 128, Runnable::run);
    }

    public RealtimeWebSocketSessionRegistry(ObjectMapper objectMapper, Executor executor) {
        this(objectMapper, 128, executor);
    }

    public RealtimeWebSocketSessionRegistry(ObjectMapper objectMapper, int sendCapacity, Executor executor) {
        this(objectMapper, sendCapacity, executor, null);
    }

    public RealtimeWebSocketSessionRegistry(ObjectMapper objectMapper, int sendCapacity, Executor executor,
                                            RealtimeMetrics metrics) {
        this.objectMapper = objectMapper;
        if (sendCapacity < 1) throw new IllegalArgumentException("sendCapacity must be positive");
        this.sendCapacity = sendCapacity;
        this.executor = executor;
        this.metrics = metrics;
    }

    public void register(WebSocketSession session) {
        sendQueues.computeIfAbsent(session, value -> new RealtimeWebSocketSendQueue(
                value, objectMapper, sendCapacity, executor));
    }

    public void touch(WebSocketSession session) {
        RealtimeWebSocketSendQueue queue = sendQueues.get(session);
        if (queue != null) queue.touch();
    }

    public boolean send(WebSocketSession session, RealtimeServerMessage<?> message) {
        register(session);
        RealtimeWebSocketSendQueue queue = sendQueues.get(session);
        if (queue.offer(message)) return true;
        queue.close(new CloseStatus(1013, "try again later"));
        return false;
    }

    public void heartbeat(Duration interval, Duration idleTimeout) {
        long now = System.currentTimeMillis();
        for (Map.Entry<WebSocketSession, RealtimeWebSocketSendQueue> entry : sendQueues.entrySet()) {
            WebSocketSession session = entry.getKey();
            RealtimeWebSocketSendQueue queue = entry.getValue();
            if (!session.isOpen()) {
                remove(session);
                continue;
            }
            if (queue.isIdle(now, idleTimeout.toMillis())) {
                queue.close(new CloseStatus(1000, "idle timeout"));
                remove(session);
                continue;
            }
            if (queue.shouldHeartbeat(now, interval.toMillis()) && !queue.offerHeartbeat()) {
                queue.close(new CloseStatus(1013, "try again later"));
            }
        }
    }

    public void subscribe(long auctionId, WebSocketSession session) {
        if (sessions.computeIfAbsent(auctionId, ignored -> ConcurrentHashMap.newKeySet()).add(session)
                && metrics != null) {
            metrics.subscriptionOpened();
        }
    }

    public void beginSync(long auctionId, WebSocketSession session, int capacity) {
        SyncBuffer previous = syncing.computeIfAbsent(session, ignored -> new ConcurrentHashMap<>())
                .put(auctionId, new SyncBuffer(capacity));
        if (previous == null && metrics != null) metrics.syncStarted();
    }

    public boolean sendSnapshot(long auctionId, WebSocketSession session, RealtimeSnapshot snapshot,
                                String requestId) {
        SyncBuffer buffer = syncing.getOrDefault(session, Map.of()).get(auctionId);
        if (buffer == null) return false;
        try {
            synchronized (buffer) {
                if (!send(session, server(RealtimeMessageType.SNAPSHOT, requestId, snapshot))) return false;
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
                    if (message != null && !send(session, (RealtimeServerMessage<?>) message)) return false;
                }
                buffer.live = true;
                Map<Long, SyncBuffer> values = syncing.get(session);
                if (values != null) {
                    boolean removed = values.remove(auctionId, buffer);
                    if (removed && metrics != null) metrics.syncFinished();
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
        if (values != null && values.remove(auctionId) != null && metrics != null) metrics.syncFinished();
    }

    public void unsubscribe(long auctionId, WebSocketSession session) {
        Set<WebSocketSession> values = sessions.get(auctionId);
        if (values != null) {
            if (values.remove(session) && metrics != null) metrics.subscriptionClosed();
            if (values.isEmpty()) sessions.remove(auctionId, values);
        }
    }

    public void remove(WebSocketSession session) {
        sessions.values().forEach(values -> {
            if (values.remove(session) && metrics != null) metrics.subscriptionClosed();
        });
        sessions.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        Map<Long, SyncBuffer> syncValues = syncing.remove(session);
        if (syncValues != null && metrics != null) {
            syncValues.keySet().forEach(ignored -> metrics.syncFinished());
        }
        RealtimeWebSocketSendQueue queue = sendQueues.remove(session);
        if (queue != null) queue.close(CloseStatus.NORMAL);
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
            if (message != null) {
                long sequenceNo = envelope.payload() instanceof BidAcceptedEvent bid ? bid.sequenceNo() : 0L;
                enqueueEvent(session, (RealtimeServerMessage<?>) message, auctionId, sequenceNo);
            }
        }
    }

    private void enqueueEvent(WebSocketSession session, RealtimeServerMessage<?> message,
                              long auctionId, long sequenceNo) {
        register(session);
        RealtimeWebSocketSendQueue queue = sendQueues.get(session);
        if (queue.offer(message)) return;
        RealtimeServerMessage<?> recovery = server(
                RealtimeMessageType.RESYNC_REQUIRED,
                UUID.randomUUID().toString(),
                new RealtimeResyncRequired(auctionId, RealtimeResyncReason.BUFFER_OVERFLOW, sequenceNo));
        queue.overflow(recovery);
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

    public void close() {
        sendQueues.values().forEach(queue -> queue.close(CloseStatus.GOING_AWAY));
        if (executor instanceof ExecutorService service) service.shutdownNow();
    }

    public void close(WebSocketSession session, CloseStatus status) {
        RealtimeWebSocketSendQueue queue = sendQueues.get(session);
        if (queue != null) queue.close(status);
    }

    public Set<WebSocketSession> sessions() {
        return Set.copyOf(sendQueues.keySet());
    }

    public int connectionCount() {
        return sendQueues.size();
    }

    public int subscriptionCount() {
        return sessions.values().stream().mapToInt(Set::size).sum();
    }

    public int syncingSubscriptionCount() {
        return syncing.values().stream().mapToInt(Map::size).sum();
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

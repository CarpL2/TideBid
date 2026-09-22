package io.github.carpl2.tidebid.realtime.infrastructure.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.contracts.RealtimeClientMessage;
import io.github.carpl2.tidebid.contracts.RealtimeClientMessageDecoder;
import io.github.carpl2.tidebid.contracts.RealtimeError;
import io.github.carpl2.tidebid.contracts.RealtimeErrorCode;
import io.github.carpl2.tidebid.contracts.RealtimeMessageType;
import io.github.carpl2.tidebid.contracts.RealtimePing;
import io.github.carpl2.tidebid.contracts.RealtimePong;
import io.github.carpl2.tidebid.contracts.RealtimeServerMessage;
import io.github.carpl2.tidebid.contracts.RealtimeSubscribe;
import io.github.carpl2.tidebid.contracts.RealtimeUnsubscribe;
import io.github.carpl2.tidebid.realtime.application.port.RealtimeConnectionLeaseStore;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeProperties;
import io.github.carpl2.tidebid.realtime.infrastructure.fanout.RealtimeWebSocketSessionRegistry;
import io.github.carpl2.tidebid.realtime.infrastructure.metrics.RealtimeMetrics;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

final class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final RealtimeMetrics metrics;
    private final RealtimeClientMessageDecoder decoder;
    private final RealtimeProperties properties;
    private final RealtimeConnectionLeaseStore leaseStore;
    private final RealtimeWebSocketSessionRegistry sessionRegistry;

    RealtimeWebSocketHandler(RealtimeMetrics metrics, ObjectMapper objectMapper,
                             RealtimeProperties properties, RealtimeConnectionLeaseStore leaseStore) {
        this(metrics, objectMapper, properties, leaseStore, null);
    }

    RealtimeWebSocketHandler(RealtimeMetrics metrics, ObjectMapper objectMapper,
                             RealtimeProperties properties, RealtimeConnectionLeaseStore leaseStore,
                             RealtimeWebSocketSessionRegistry sessionRegistry) {
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.decoder = new RealtimeClientMessageDecoder(
                objectMapper, Math.toIntExact(properties.queue().maxClientMessageSize().toBytes()));
        this.properties = properties;
        this.leaseStore = leaseStore;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        metrics.connectionOpened();
        String connectionId = String.valueOf(session.getAttributes()
                .get(RealtimeWebSocketHandshakeInterceptor.CONNECTION_ID_ATTRIBUTE));
        RealtimeServerMessage<?> connected = new RealtimeServerMessage<>(
                RealtimeMessageType.CONNECTED, 1, connectionId,
                Instant.now(), new io.github.carpl2.tidebid.contracts.RealtimeConnected(
                        connectionId, Instant.now(), Math.toIntExact(properties.heartbeat().interval().toSeconds())));
        session.getAttributes().put(SubscriptionState.class.getName(), new SubscriptionState(
                properties.subscription().maxPerConnection(), properties.queue().controlWindow(),
                properties.queue().controlMaxMessages()));
        send(session, connected);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        SubscriptionState state = state(session);
        if (!state.allowControl()) {
            sendError(session, "", RealtimeErrorCode.RATE_LIMITED, "control message rate exceeded", false);
            session.close(new CloseStatus(1008, "policy violation"));
            return;
        }
        byte[] body = message.getPayload().getBytes(StandardCharsets.UTF_8);
        try {
            RealtimeClientMessage<?> decoded = decoder.decode(body);
            switch (decoded.type()) {
                case PING -> {
                    RealtimePing ping = (RealtimePing) decoded.payload();
                    send(session, new RealtimeServerMessage<>(RealtimeMessageType.PONG, 1,
                            decoded.requestId(), Instant.now(), new RealtimePong(Instant.now(), ping.clientTime())));
                }
                case SUBSCRIBE -> {
                    RealtimeSubscribe subscribe = (RealtimeSubscribe) decoded.payload();
                    if (!state.subscribe(subscribe.auctionId())) {
                        sendError(session, decoded.requestId(), RealtimeErrorCode.SUBSCRIPTION_LIMIT_EXCEEDED,
                                "subscription limit exceeded", true);
                    }
                    if (sessionRegistry != null) sessionRegistry.subscribe(subscribe.auctionId(), session);
                }
                case UNSUBSCRIBE -> {
                    long auctionId = ((RealtimeUnsubscribe) decoded.payload()).auctionId();
                    state.unsubscribe(auctionId);
                    if (sessionRegistry != null) sessionRegistry.unsubscribe(auctionId, session);
                }
                default -> throw new IllegalArgumentException("unsupported client message");
            }
        } catch (RealtimeClientMessageDecoder.MessageRejectedException exception) {
            if (exception.reason() == RealtimeClientMessageDecoder.RejectionReason.MESSAGE_TOO_LARGE) {
                session.close(new CloseStatus(1009, "message too large"));
            } else {
                sendError(session, "", RealtimeErrorCode.INVALID_MESSAGE, "invalid message", true);
            }
        } catch (IllegalArgumentException exception) {
            sendError(session, "", RealtimeErrorCode.INVALID_MESSAGE, "invalid message", true);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        metrics.connectionClosed();
        if (sessionRegistry != null) sessionRegistry.remove(session);
        Object userId = session.getAttributes().get(RealtimeWebSocketHandshakeInterceptor.USER_ID_ATTRIBUTE);
        Object connectionId = session.getAttributes().get(RealtimeWebSocketHandshakeInterceptor.CONNECTION_ID_ATTRIBUTE);
        if (userId instanceof Long user && connectionId instanceof String id) {
            leaseStore.release(user, id);
        }
    }

    private SubscriptionState state(WebSocketSession session) {
        return (SubscriptionState) session.getAttributes().get(SubscriptionState.class.getName());
    }

    private void send(WebSocketSession session, RealtimeServerMessage<?> message) throws Exception {
        synchronized (session) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        }
    }

    private void sendError(WebSocketSession session, String requestId, RealtimeErrorCode code,
                           String message, boolean recoverable) throws Exception {
        send(session, new RealtimeServerMessage<>(RealtimeMessageType.ERROR, 1,
                requestId.isBlank() ? UUID.randomUUID().toString() : requestId,
                Instant.now(), new RealtimeError(code, message, recoverable)));
    }

    private static final class SubscriptionState {
        private final int maxSubscriptions;
        private final long controlWindowMillis;
        private final int controlMaxMessages;
        private final Set<Long> subscriptions = new HashSet<>();
        private final java.util.ArrayDeque<Long> controlTimes = new java.util.ArrayDeque<>();

        private SubscriptionState(int maxSubscriptions, java.time.Duration controlWindow, int controlMaxMessages) {
            this.maxSubscriptions = maxSubscriptions;
            this.controlWindowMillis = controlWindow.toMillis();
            this.controlMaxMessages = controlMaxMessages;
        }

        private synchronized boolean allowControl() {
            long now = System.currentTimeMillis();
            while (!controlTimes.isEmpty() && controlTimes.peekFirst() <= now - controlWindowMillis) {
                controlTimes.removeFirst();
            }
            if (controlTimes.size() >= controlMaxMessages) return false;
            controlTimes.addLast(now);
            return true;
        }

        private synchronized boolean subscribe(long auctionId) {
            if (subscriptions.contains(auctionId)) return true;
            if (subscriptions.size() >= maxSubscriptions) return false;
            subscriptions.add(auctionId);
            return true;
        }

        private synchronized void unsubscribe(long auctionId) { subscriptions.remove(auctionId); }
    }
}

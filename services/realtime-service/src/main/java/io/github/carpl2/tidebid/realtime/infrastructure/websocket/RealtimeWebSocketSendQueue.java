package io.github.carpl2.tidebid.realtime.infrastructure.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.contracts.RealtimeServerMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Serializes writes and bounds memory used by a slow WebSocket client. */
public final class RealtimeWebSocketSendQueue {

    private final WebSocketSession session;
    private final ObjectMapper objectMapper;
    private final ArrayBlockingQueue<WebSocketMessage<?>> messages;
    private final Executor executor;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong lastActivityMillis = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastHeartbeatMillis = new AtomicLong(System.currentTimeMillis());
    private volatile CloseStatus closeAfterDrain;

    public RealtimeWebSocketSendQueue(WebSocketSession session, ObjectMapper objectMapper,
                               int capacity, Executor executor) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.messages = new ArrayBlockingQueue<>(capacity);
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    public boolean offer(RealtimeServerMessage<?> message) {
        if (closed.get()) return false;
        try {
            String encoded = objectMapper.writeValueAsString(message);
            if (!messages.offer(new TextMessage(encoded))) return false;
            scheduleDrain();
            return true;
        } catch (Exception exception) {
            close(CloseStatus.SERVER_ERROR);
            return false;
        }
    }

    public void overflow(RealtimeServerMessage<?> recoveryMessage) {
        if (closed.get()) return;
        try {
            String encoded = objectMapper.writeValueAsString(recoveryMessage);
            messages.clear();
            messages.offer(new TextMessage(encoded));
            closeAfterDrain = new CloseStatus(1013, "try again later");
            scheduleDrain();
        } catch (Exception exception) {
            close(CloseStatus.SERVER_ERROR);
        }
    }

    public void close(CloseStatus status) {
        if (!closed.compareAndSet(false, true)) return;
        messages.clear();
        try {
            session.close(status);
        } catch (Exception ignored) {
            // The container owns the final close if the socket is already gone.
        }
    }

    public void closeAfterDrain(CloseStatus status) {
        closeAfterDrain = status;
        scheduleDrain();
    }

    public boolean offerHeartbeat() {
        if (closed.get() || !messages.offer(new PingMessage())) return false;
        lastHeartbeatMillis.set(System.currentTimeMillis());
        scheduleDrain();
        return true;
    }

    public void touch() {
        lastActivityMillis.set(System.currentTimeMillis());
    }

    public boolean isIdle(long now, long idleTimeoutMillis) {
        return now - lastActivityMillis.get() >= idleTimeoutMillis;
    }

    public boolean shouldHeartbeat(long now, long intervalMillis) {
        return now - lastHeartbeatMillis.get() >= intervalMillis;
    }

    private void scheduleDrain() {
        if (draining.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
    }

    private void drain() {
        try {
            WebSocketMessage<?> message;
            while ((message = messages.poll()) != null) {
                if (!session.isOpen()) {
                    closed.set(true);
                    return;
                }
                session.sendMessage(message);
            }
            CloseStatus status = closeAfterDrain;
            if (status != null) {
                closed.set(true);
                closeAfterDrain = null;
                try {
                    session.close(status);
                } catch (Exception ignored) {
                    // Ignore a close race with the container.
                }
            }
        } catch (Exception exception) {
            close(CloseStatus.SERVER_ERROR);
        } finally {
            draining.set(false);
            if (!messages.isEmpty() && !closed.get()) scheduleDrain();
        }
    }
}

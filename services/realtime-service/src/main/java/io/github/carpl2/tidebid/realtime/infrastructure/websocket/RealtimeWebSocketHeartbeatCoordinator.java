package io.github.carpl2.tidebid.realtime.infrastructure.websocket;

import io.github.carpl2.tidebid.realtime.application.port.RealtimeConnectionLeaseStore;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeProperties;
import io.github.carpl2.tidebid.realtime.infrastructure.fanout.RealtimeWebSocketSessionRegistry;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class RealtimeWebSocketHeartbeatCoordinator implements AutoCloseable {

    private final RealtimeWebSocketSessionRegistry sessionRegistry;
    private final RealtimeConnectionLeaseStore leaseStore;
    private final RealtimeProperties properties;
    private final ScheduledExecutorService scheduler;

    RealtimeWebSocketHeartbeatCoordinator(RealtimeWebSocketSessionRegistry sessionRegistry,
                                           RealtimeConnectionLeaseStore leaseStore,
                                           RealtimeProperties properties,
                                           ScheduledExecutorService scheduler) {
        this.sessionRegistry = sessionRegistry;
        this.leaseStore = leaseStore;
        this.properties = properties;
        this.scheduler = scheduler;
        Duration interval = properties.heartbeat().interval();
        scheduler.scheduleAtFixedRate(this::tick, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            sessionRegistry.heartbeat(properties.heartbeat().interval(), properties.heartbeat().idleTimeout());
            for (WebSocketSession session : sessionRegistry.sessions()) {
                try {
                    Object userId = session.getAttributes().get(RealtimeWebSocketAttributes.USER_ID);
                    Object connectionId = session.getAttributes().get(RealtimeWebSocketAttributes.CONNECTION_ID);
                    if (!(userId instanceof Long user) || !(connectionId instanceof String connection)) continue;
                    if (!leaseStore.renew(user, connection, properties.connection().leaseTtl())) {
                        sessionRegistry.close(session, new CloseStatus(1013, "connection lease expired"));
                    }
                } catch (RuntimeException ignored) {
                    // One failed lease renewal must not stop maintenance for other sessions.
                }
            }
        } catch (RuntimeException exception) {
            // A failed lease renewal is handled on the next tick; no credentials or payloads are logged.
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}

package io.github.carpl2.tidebid.realtime.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public final class RealtimeMetrics {

    private final AtomicLong connections = new AtomicLong();
    private final AtomicLong subscriptions = new AtomicLong();
    private final AtomicLong syncingSubscriptions = new AtomicLong();
    private final Counter snapshotSuccess;
    private final Counter snapshotFailure;
    private final Counter handshakeSuccess;
    private final Counter handshakeFailure;

    public RealtimeMetrics(MeterRegistry registry) {
        Gauge.builder("tidebid.realtime.connections", connections, AtomicLong::get).register(registry);
        Gauge.builder("tidebid.realtime.subscriptions", subscriptions, AtomicLong::get).register(registry);
        Gauge.builder("tidebid.realtime.subscriptions.syncing", syncingSubscriptions, AtomicLong::get)
                .register(registry);
        snapshotSuccess = Counter.builder("tidebid.realtime.snapshot.requests")
                .tag("outcome", "success").register(registry);
        snapshotFailure = Counter.builder("tidebid.realtime.snapshot.requests")
                .tag("outcome", "failure").register(registry);
        handshakeSuccess = Counter.builder("tidebid.realtime.websocket.handshakes")
                .tag("outcome", "accepted").register(registry);
        handshakeFailure = Counter.builder("tidebid.realtime.websocket.handshakes")
                .tag("outcome", "rejected").register(registry);
    }

    public AtomicLong connections() { return connections; }
    public AtomicLong subscriptions() { return subscriptions; }
    public AtomicLong syncingSubscriptions() { return syncingSubscriptions; }
    public void snapshotSucceeded() { snapshotSuccess.increment(); }
    public void snapshotFailed() { snapshotFailure.increment(); }
    public void websocketHandshakeAccepted() { handshakeSuccess.increment(); }
    public void websocketHandshakeRejected(String reason) { handshakeFailure.increment(); }
    public void connectionOpened() { connections.incrementAndGet(); }
    public void connectionClosed() { connections.updateAndGet(value -> Math.max(0, value - 1)); }
}

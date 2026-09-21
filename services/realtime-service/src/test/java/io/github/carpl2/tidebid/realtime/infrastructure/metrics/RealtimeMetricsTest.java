package io.github.carpl2.tidebid.realtime.infrastructure.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeMetricsTest {

    @Test
    void usesOnlyBoundedMetricTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RealtimeMetrics metrics = new RealtimeMetrics(registry);
        metrics.connections().incrementAndGet();
        metrics.subscriptions().addAndGet(2);
        metrics.syncingSubscriptions().incrementAndGet();
        metrics.snapshotSucceeded();
        metrics.snapshotFailed();

        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags())
                        .noneMatch(tag -> tag.getKey().equals("userId")
                                || tag.getKey().equals("auctionId")
                                || tag.getKey().equals("eventId")));
        assertThat(registry.get("tidebid.realtime.connections").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("tidebid.realtime.subscriptions").gauge().value()).isEqualTo(2.0);
    }
}

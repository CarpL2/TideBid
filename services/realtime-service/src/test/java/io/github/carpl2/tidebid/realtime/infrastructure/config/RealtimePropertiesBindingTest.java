package io.github.carpl2.tidebid.realtime.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimePropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(validProperties());

    @Test
    void bindsValidConfiguration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RealtimeProperties.class).queue().sendCapacity()).isEqualTo(128);
        });
    }

    @Test
    void invalidQueueCapacityFailsStartup() {
        runner.withPropertyValues("tidebid.realtime.queue.send-capacity=4")
                .run(context -> assertThat(context).hasFailed());
    }

    private static String[] validProperties() {
        return new String[] {
                "tidebid.realtime.ticket.ttl=30s",
                "tidebid.realtime.ticket.rate-limit-window=60s",
                "tidebid.realtime.ticket.rate-limit-max-requests=10",
                "tidebid.realtime.connection.max-per-user=5",
                "tidebid.realtime.connection.lease-ttl=120s",
                "tidebid.realtime.subscription.max-per-connection=20",
                "tidebid.realtime.subscription.sync-buffer-capacity=128",
                "tidebid.realtime.heartbeat.interval=30s",
                "tidebid.realtime.heartbeat.idle-timeout=90s",
                "tidebid.realtime.queue.send-capacity=128",
                "tidebid.realtime.queue.max-client-message-size=8KB",
                "tidebid.realtime.queue.control-window=10s",
                "tidebid.realtime.queue.control-max-messages=30",
                "tidebid.realtime.auction-client.enabled=false",
                "tidebid.realtime.auction-client.base-url=",
                "tidebid.realtime.auction-client.internal-token=",
                "tidebid.realtime.auction-client.connect-timeout=2s",
                "tidebid.realtime.auction-client.read-timeout=3s",
                "tidebid.realtime.redis.enabled=false",
                "tidebid.realtime.redis.event-dedup-ttl=2h",
                "tidebid.realtime.rocketmq.enabled=false",
                "tidebid.realtime.rocketmq.endpoints=127.0.0.1:8081",
                "tidebid.realtime.rocketmq.request-timeout=3s",
                "tidebid.realtime.rocketmq.consumer-group=tidebid-realtime-auction-v1",
                "tidebid.realtime.rocketmq.auction-events-topic=tidebid-auction-events"
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RealtimeProperties.class)
    static class PropertiesConfiguration { }
}

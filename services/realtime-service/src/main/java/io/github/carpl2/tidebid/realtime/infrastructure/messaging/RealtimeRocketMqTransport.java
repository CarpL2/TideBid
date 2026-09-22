package io.github.carpl2.tidebid.realtime.infrastructure.messaging;

import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile({"local-db", "nacos"})
public final class RealtimeRocketMqTransport implements SmartLifecycle {

    private final RealtimeProperties properties;
    private final RealtimeAuctionEventHandler handler;
    private final MeterRegistry meters;
    private final ClientServiceProvider provider;
    private final Map<String, PushConsumer> consumers = new ConcurrentHashMap<>();
    private volatile boolean running;

    @Autowired
    public RealtimeRocketMqTransport(RealtimeProperties properties, RealtimeAuctionEventHandler handler,
                                     MeterRegistry meters) {
        this(properties, handler, meters, ClientServiceProvider.loadService());
    }

    RealtimeRocketMqTransport(RealtimeProperties properties, RealtimeAuctionEventHandler handler,
                              MeterRegistry meters, ClientServiceProvider provider) {
        this.properties = Objects.requireNonNull(properties);
        this.handler = Objects.requireNonNull(handler);
        this.meters = Objects.requireNonNull(meters);
        this.provider = Objects.requireNonNull(provider);
    }

    @Override public void start() { running = true; }

    @Scheduled(initialDelay = 0, fixedDelay = 5_000)
    public void connectMissingConsumers() {
        if (!running || !properties.rocketmq().enabled()
                || consumers.containsKey(properties.rocketmq().consumerGroup())) return;
        try {
            PushConsumer consumer = provider.newPushConsumerBuilder()
                    .setClientConfiguration(clientConfiguration())
                    .setConsumerGroup(properties.rocketmq().consumerGroup())
                    .setSubscriptionExpressions(Map.of(
                            properties.rocketmq().auctionEventsTopic(),
                            new FilterExpression(io.github.carpl2.tidebid.contracts.RocketMqTopology.REALTIME_AUCTION_EVENT_TAGS,
                                    FilterExpressionType.TAG)))
                    .setMessageListener(message -> consume(message))
                    .build();
            PushConsumer previous = consumers.putIfAbsent(properties.rocketmq().consumerGroup(), consumer);
            if (previous != null) {
                try { consumer.close(); } catch (java.io.IOException ignored) { }
            }
        } catch (RuntimeException | ClientException exception) {
            meters.counter("tidebid.realtime.rocketmq.consumer.connect", "outcome", "failure").increment();
        }
    }

    ConsumeResult consume(MessageView message) {
        try {
            handler.handle(toInboundMessage(message));
            meters.counter("tidebid.realtime.rocketmq.consume", "outcome", "success").increment();
            return ConsumeResult.SUCCESS;
        } catch (RuntimeException exception) {
            meters.counter("tidebid.realtime.rocketmq.consume", "outcome", "failure").increment();
            return ConsumeResult.FAILURE;
        }
    }

    @Override public synchronized void stop() {
        running = false;
        new ArrayList<>(consumers.values()).forEach(c -> { try { c.close(); } catch (Exception ignored) { } });
        consumers.clear();
    }

    @Override public boolean isRunning() { return running; }

    private ClientConfiguration clientConfiguration() {
        return ClientConfiguration.newBuilder().setEndpoints(properties.rocketmq().endpoints())
                .setRequestTimeout(properties.rocketmq().requestTimeout()).enableSsl(false)
                .enableVirtualThreads(false).setMaxStartupAttempts(1).build();
    }

    private static RealtimeInboundMessage toInboundMessage(MessageView view) {
        ByteBuffer body = view.getBody().asReadOnlyBuffer();
        byte[] bytes = new byte[body.remaining()]; body.get(bytes);
        return new RealtimeInboundMessage(view.getMessageId().toString(), view.getTopic(),
                view.getTag().orElse(null), view.getKeys(), view.getProperties(), bytes);
    }

    public record RealtimeInboundMessage(String messageId, String topic, String tag,
                                         Collection<String> keys, Map<String, String> properties, byte[] body) {
        public RealtimeInboundMessage { keys = List.copyOf(keys); properties = Map.copyOf(properties); body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }
}

package io.github.carpl2.tidebid.trade.infrastructure.messaging;

import io.github.carpl2.tidebid.trade.infrastructure.config.TradeRocketMqProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Owns the bounded RocketMQ clients used by the Trade process. */
@Component
@Profile({"local-db", "nacos"})
public class TradeRocketMqTransport implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(TradeRocketMqTransport.class);
    private static final String SERVICE = "trade";
    private final TradeRocketMqProperties properties;
    private final List<InboundHandler> handlers;
    private final MeterRegistry meters;
    private final ClientServiceProvider provider;
    private final Map<String, PushConsumer> consumers = new ConcurrentHashMap<>();
    private volatile Producer producer;
    private volatile boolean running;

    @Autowired
    public TradeRocketMqTransport(TradeRocketMqProperties properties,
                                  List<InboundHandler> handlers, MeterRegistry meters) {
        this(properties, handlers, meters, ClientServiceProvider.loadService());
    }

    TradeRocketMqTransport(TradeRocketMqProperties properties, List<InboundHandler> handlers,
                           MeterRegistry meters, ClientServiceProvider provider) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.handlers = List.copyOf(handlers);
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
    }

    public String send(JdbcTradeOutboxRepository.OutboxEntity outbox) {
        Objects.requireNonNull(outbox, "outbox must not be null");
        if (!running) throw new IllegalStateException("RocketMQ transport is stopping");
        try {
            SendReceipt receipt = producer().send(buildMessage(outbox));
            return receipt.getMessageId().toString();
        } catch (ClientException exception) {
            throw new RocketMqPublishException("RocketMQ send failed", exception);
        }
    }

    @Override
    public void start() {
        running = true;
    }

    @Scheduled(initialDelay = 0, fixedDelay = 5_000)
    public void connectMissingConsumers() {
        if (!running) return;
        for (InboundHandler handler : handlers) {
            ConsumerBinding binding = handler.binding();
            if (consumers.containsKey(binding.consumerGroup())) continue;
            try {
                PushConsumer consumer = provider.newPushConsumerBuilder()
                        .setClientConfiguration(clientConfiguration())
                        .setConsumerGroup(binding.consumerGroup())
                        .setSubscriptionExpressions(toExpressions(binding.subscriptions()))
                        .setMessageListener(message -> consumeSynchronously(handler, message))
                        .build();
                PushConsumer raced = consumers.putIfAbsent(binding.consumerGroup(), consumer);
                if (raced != null) {
                    closeQuietly(consumer);
                } else {
                    LOGGER.info("RocketMQ consumer connected: service={}, consumerGroup={}",
                            SERVICE, binding.consumerGroup());
                }
            } catch (RuntimeException | ClientException exception) {
                meters.counter("tidebid.rocketmq.consumer.connect", "service", SERVICE, "outcome", "failure")
                        .increment();
                LOGGER.warn("RocketMQ consumer connection deferred: service={}, consumerGroup={}, errorCode={}",
                        SERVICE, binding.consumerGroup(), errorCode(exception));
            }
        }
    }

    ConsumeResult consumeSynchronously(InboundHandler handler, MessageView message) {
        String group = handler.binding().consumerGroup();
        try {
            handler.handle(toInboundMessage(message));
            meters.counter("tidebid.rocketmq.consume", "service", SERVICE,
                    "consumerGroup", group, "outcome", "success").increment();
            return ConsumeResult.SUCCESS;
        } catch (RuntimeException exception) {
            meters.counter("tidebid.rocketmq.consume", "service", SERVICE,
                    "consumerGroup", group, "outcome", "failure").increment();
            LOGGER.warn("RocketMQ consumption will retry: service={}, consumerGroup={}, messageId={}, errorCode={}",
                    SERVICE, group, message.getMessageId(), errorCode(exception));
            return ConsumeResult.FAILURE;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        List<PushConsumer> opened = new ArrayList<>(consumers.values());
        consumers.clear();
        opened.forEach(TradeRocketMqTransport::closeQuietly);
        if (producer != null) {
            closeQuietly(producer);
            producer = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    int consumerCount() {
        return consumers.size();
    }

    private synchronized Producer producer() throws ClientException {
        if (producer == null) {
            producer = provider.newProducerBuilder()
                    .setClientConfiguration(clientConfiguration())
                    .setTopics(properties.topics().tradeEvents(), properties.topics().scheduledCommands())
                    .setMaxAttempts(properties.producerRetryAttempts() + 1)
                    .build();
            LOGGER.info("RocketMQ producer connected: service={}", SERVICE);
        }
        return producer;
    }

    private Message buildMessage(JdbcTradeOutboxRepository.OutboxEntity outbox) {
        MessageBuilder builder = provider.newMessageBuilder()
                .setTopic(outbox.topic()).setTag(outbox.tag()).setKeys(outbox.messageKey())
                .addProperty("eventId", outbox.eventId())
                .addProperty("eventType", outbox.eventType())
                .addProperty("schemaVersion", Integer.toString(outbox.schemaVersion()))
                .addProperty("payloadHash", outbox.payloadHash())
                .setBody(outbox.payload().getBytes(StandardCharsets.UTF_8));
        if (outbox.deliverAt().isAfter(Instant.now())) {
            builder.setDeliveryTimestamp(outbox.deliverAt().toEpochMilli());
        }
        return builder.build();
    }

    private ClientConfiguration clientConfiguration() {
        return ClientConfiguration.newBuilder().setEndpoints(properties.endpoints())
                .setRequestTimeout(properties.requestTimeout()).enableSsl(false)
                .enableVirtualThreads(false).setMaxStartupAttempts(1).build();
    }

    private static Map<String, FilterExpression> toExpressions(Map<String, String> subscriptions) {
        Map<String, FilterExpression> expressions = new LinkedHashMap<>();
        subscriptions.forEach((topic, tags) -> expressions.put(topic,
                "*".equals(tags) ? FilterExpression.SUB_ALL
                        : new FilterExpression(tags, FilterExpressionType.TAG)));
        return Map.copyOf(expressions);
    }

    private static InboundMessage toInboundMessage(MessageView view) {
        ByteBuffer body = view.getBody().asReadOnlyBuffer();
        byte[] bytes = new byte[body.remaining()];
        body.get(bytes);
        return new InboundMessage(view.getMessageId().toString(), view.getTopic(), view.getTag().orElse(null),
                view.getKeys(), view.getProperties(), bytes, view.getDeliveryAttempt());
    }

    private static String errorCode(Throwable exception) {
        String value = exception.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "_").toUpperCase();
        return value.isBlank() ? "UNKNOWN" : value;
    }

    private static void closeQuietly(AutoCloseable client) {
        try {
            client.close();
        } catch (Exception exception) {
            LOGGER.warn("RocketMQ client close failed: service={}, errorCode={}", SERVICE, errorCode(exception));
        }
    }

    public interface InboundHandler {
        ConsumerBinding binding();

        /** Return only after the local business transaction, downstream Outbox and Inbox commit. */
        void handle(InboundMessage message);
    }

    public record ConsumerBinding(String consumerGroup, Map<String, String> subscriptions) {
        public ConsumerBinding {
            if (consumerGroup == null || consumerGroup.isBlank())
                throw new IllegalArgumentException("consumerGroup must not be blank");
            subscriptions = Map.copyOf(subscriptions);
            if (subscriptions.isEmpty()) throw new IllegalArgumentException("subscriptions must not be empty");
        }
    }

    public record InboundMessage(String messageId, String topic, String tag, Collection<String> keys,
                                 Map<String, String> properties, byte[] body, int deliveryAttempt) {
        public InboundMessage {
            keys = List.copyOf(keys);
            properties = Map.copyOf(properties);
            body = body.clone();
        }

        @Override
        public byte[] body() { return body.clone(); }
    }

    public static final class RocketMqPublishException extends RuntimeException {
        RocketMqPublishException(String message, Throwable cause) { super(message, cause); }
    }
}

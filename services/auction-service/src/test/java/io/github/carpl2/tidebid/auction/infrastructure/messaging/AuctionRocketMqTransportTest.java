package io.github.carpl2.tidebid.auction.infrastructure.messaging;

import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionRocketMqProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.consumer.PushConsumerBuilder;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.ProducerBuilder;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionRocketMqTransportTest {

    private static final Instant NOW = Instant.parse("2026-09-19T08:00:00Z");
    private static final String EMPTY_JSON_SHA256 =
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";

    @Test
    void reusesOneProducerAndClosesItOnShutdown() throws Exception {
        ClientServiceProvider provider = mock(ClientServiceProvider.class);
        ProducerBuilder producerBuilder = mock(ProducerBuilder.class);
        Producer producer = mock(Producer.class);
        MessageBuilder messageBuilder = mock(MessageBuilder.class);
        Message message = mock(Message.class);
        SendReceipt receipt = mock(SendReceipt.class);
        MessageId messageId = mock(MessageId.class);
        when(provider.newProducerBuilder()).thenReturn(producerBuilder);
        when(producerBuilder.setClientConfiguration(any())).thenReturn(producerBuilder);
        when(producerBuilder.setTopics(any(String[].class))).thenReturn(producerBuilder);
        when(producerBuilder.setMaxAttempts(3)).thenReturn(producerBuilder);
        when(producerBuilder.build()).thenReturn(producer);
        when(provider.newMessageBuilder()).thenReturn(messageBuilder);
        when(messageBuilder.setTopic(any())).thenReturn(messageBuilder);
        when(messageBuilder.setTag(any())).thenReturn(messageBuilder);
        when(messageBuilder.setKeys(any(String[].class))).thenReturn(messageBuilder);
        when(messageBuilder.addProperty(any(), any())).thenReturn(messageBuilder);
        when(messageBuilder.setBody(any())).thenReturn(messageBuilder);
        when(messageBuilder.build()).thenReturn(message);
        when(producer.send(message)).thenReturn(receipt);
        when(receipt.getMessageId()).thenReturn(messageId);
        when(messageId.toString()).thenReturn("broker-message-1");
        var transport = transport(provider, List.of(), new SimpleMeterRegistry());

        transport.start();
        assertThat(transport.send(outbox())).isEqualTo("broker-message-1");
        assertThat(transport.send(outbox())).isEqualTo("broker-message-1");
        verify(provider, times(1)).newProducerBuilder();
        verify(messageBuilder, never()).setDeliveryTimestamp(anyLong());

        transport.stop();
        verify(producer).close();
    }

    @Test
    void connectsOnePushConsumerPerGroupAndDoesNotConnectDuringStart() throws Exception {
        ClientServiceProvider provider = mock(ClientServiceProvider.class);
        PushConsumerBuilder builder = mock(PushConsumerBuilder.class);
        PushConsumer consumer = mock(PushConsumer.class);
        when(provider.newPushConsumerBuilder()).thenReturn(builder);
        when(builder.setClientConfiguration(any())).thenReturn(builder);
        when(builder.setConsumerGroup(any())).thenReturn(builder);
        when(builder.setSubscriptionExpressions(any())).thenReturn(builder);
        when(builder.setMessageListener(any())).thenReturn(builder);
        when(builder.build()).thenReturn(consumer);
        var handler = handler(false, new AtomicInteger());
        var transport = transport(provider, List.of(handler), new SimpleMeterRegistry());

        transport.start();
        verify(provider, never()).newPushConsumerBuilder();
        transport.connectMissingConsumers();
        transport.connectMissingConsumers();

        assertThat(transport.consumerCount()).isOne();
        verify(builder, times(1)).build();
        transport.stop();
        verify(consumer).close();
    }

    @Test
    void acknowledgesOnlyAfterTheSynchronousHandlerReturnsSuccessfully() {
        ClientServiceProvider provider = mock(ClientServiceProvider.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AtomicInteger calls = new AtomicInteger();
        var transport = transport(provider, List.of(), meters);
        MessageView message = messageView();

        assertThat(transport.consumeSynchronously(handler(false, calls), message)).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(calls).hasValue(1);
        assertThat(transport.consumeSynchronously(handler(true, calls), message)).isEqualTo(ConsumeResult.FAILURE);
        assertThat(calls).hasValue(2);
        assertThat(meters.counter("tidebid.rocketmq.consume", "service", "auction",
                "consumerGroup", "test-group", "outcome", "success").count()).isEqualTo(1);
        assertThat(meters.counter("tidebid.rocketmq.consume", "service", "auction",
                "consumerGroup", "test-group", "outcome", "failure").count()).isEqualTo(1);
    }

    @Test
    void neverAcknowledgesAPermanentPoisonMessageEvenAtALateDeliveryAttempt() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AtomicInteger calls = new AtomicInteger();
        var transport = transport(mock(ClientServiceProvider.class), List.of(), meters);

        assertThat(transport.consumeSynchronously(handler(true, calls), messageView(17)))
                .isEqualTo(ConsumeResult.FAILURE);
        assertThat(calls).hasValue(1);
        assertThat(meters.counter("tidebid.rocketmq.consume", "service", "auction",
                "consumerGroup", "test-group", "outcome", "failure").count()).isEqualTo(1);
    }

    @Test
    void reschedulesAnOverdueCommandOneSecondIntoTheFuture() {
        ClientServiceProvider provider = mock(ClientServiceProvider.class);
        MessageBuilder builder = mock(MessageBuilder.class);
        Message message = mock(Message.class);
        when(provider.newMessageBuilder()).thenReturn(builder);
        when(builder.setTopic(any())).thenReturn(builder);
        when(builder.setTag(any())).thenReturn(builder);
        when(builder.setKeys(any(String[].class))).thenReturn(builder);
        when(builder.addProperty(any(), any())).thenReturn(builder);
        when(builder.setBody(any())).thenReturn(builder);
        when(builder.setDeliveryTimestamp(anyLong())).thenReturn(builder);
        when(builder.build()).thenReturn(message);
        var properties = new AuctionRocketMqProperties(
                "127.0.0.1:8081", Duration.ofSeconds(3), 2,
                new AuctionRocketMqProperties.Topics("tidebid-auction-events", "tidebid-scheduled-commands"),
                new AuctionRocketMqProperties.ConsumerGroups("tidebid-auction-close-v1"));
        var transport = new AuctionRocketMqTransport(properties, List.of(), new SimpleMeterRegistry(),
                provider, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(transport.buildMessage(outbox("tidebid-scheduled-commands", NOW.minusSeconds(30))))
                .isSameAs(message);
        verify(builder).addProperty("payloadHash", EMPTY_JSON_SHA256);
        verify(builder).setDeliveryTimestamp(NOW.plusSeconds(1).toEpochMilli());
    }

    private static AuctionRocketMqTransport transport(ClientServiceProvider provider,
                                                       List<AuctionRocketMqTransport.InboundHandler> handlers,
                                                       SimpleMeterRegistry meters) {
        var properties = new AuctionRocketMqProperties(
                "127.0.0.1:8081", Duration.ofSeconds(3), 2,
                new AuctionRocketMqProperties.Topics("tidebid-auction-events", "tidebid-scheduled-commands"),
                new AuctionRocketMqProperties.ConsumerGroups("tidebid-auction-close-v1"));
        return new AuctionRocketMqTransport(properties, handlers, meters, provider);
    }

    private static AuctionRocketMqTransport.InboundHandler handler(boolean fail, AtomicInteger calls) {
        return new AuctionRocketMqTransport.InboundHandler() {
            @Override
            public AuctionRocketMqTransport.ConsumerBinding binding() {
                return new AuctionRocketMqTransport.ConsumerBinding(
                        "test-group", Map.of("tidebid-auction-events", "auction.closed"));
            }

            @Override
            public void handle(AuctionRocketMqTransport.InboundMessage message) {
                calls.incrementAndGet();
                if (fail) throw new IllegalStateException("transaction rolled back");
            }
        };
    }

    private static MessageView messageView() {
        return messageView(1);
    }

    private static MessageView messageView(int deliveryAttempt) {
        MessageView view = mock(MessageView.class);
        MessageId id = mock(MessageId.class);
        when(id.toString()).thenReturn("message-1");
        when(view.getMessageId()).thenReturn(id);
        when(view.getTopic()).thenReturn("tidebid-auction-events");
        when(view.getBody()).thenReturn(ByteBuffer.wrap("{}".getBytes()));
        when(view.getProperties()).thenReturn(Map.of("eventId", "event-1"));
        when(view.getTag()).thenReturn(Optional.of("auction.closed"));
        when(view.getKeys()).thenReturn(List.of("event-1"));
        when(view.getDeliveryAttempt()).thenReturn(deliveryAttempt);
        return view;
    }

    private static JdbcAuctionOutboxRepository.OutboxEntity outbox() {
        return outbox("tidebid-auction-events", NOW);
    }

    private static JdbcAuctionOutboxRepository.OutboxEntity outbox(String topic, Instant deliverAt) {
        return new JdbcAuctionOutboxRepository.OutboxEntity(
                1L, "event-1", "AUCTION", "1001", "auction.closed", 1,
                topic, "auction.closed", "event-1", "{}", "a".repeat(64),
                deliverAt, "PUBLISHING", 0, NOW, "auction-test", "lease-1",
                NOW.plusSeconds(30), null, null, NOW, NOW);
    }
}

package io.github.carpl2.tidebid.trade.infrastructure.messaging;

import io.github.carpl2.tidebid.contracts.RocketMqTopology;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeRocketMqProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeMessagingReliabilityTest {

    private static final Instant NOW = Instant.parse("2026-09-19T08:00:00Z");
    private static final String EMPTY_JSON_SHA256 =
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";

    @Test
    void republishesAfterBrokerAckWhenTheLocalPublishedMarkFails() {
        JdbcTradeOutboxRepository repository = mock(JdbcTradeOutboxRepository.class);
        TradeRocketMqTransport transport = mock(TradeRocketMqTransport.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var firstLease = outbox("lease-ack-lost-1");
        var retryLease = outbox("lease-ack-lost-2");
        when(repository.claimBatch("trade-test", NOW))
                .thenReturn(List.of(firstLease))
                .thenReturn(List.of(retryLease));
        when(repository.diagnostics(NOW)).thenReturn(
                new JdbcTradeOutboxRepository.OutboxDiagnostics(1, 0, 0))
                .thenReturn(new JdbcTradeOutboxRepository.OutboxDiagnostics(0, 0, 0));
        when(transport.send(firstLease)).thenReturn("broker-message-first");
        when(transport.send(retryLease)).thenReturn("broker-message-retry");
        when(repository.markPublished(firstLease.eventId(), firstLease.leaseToken(), NOW))
                .thenThrow(new IllegalStateException("database unavailable after broker acknowledgement"));
        when(repository.markFailed(firstLease.eventId(), firstLease.leaseToken(),
                "ILLEGALSTATEEXCEPTION", NOW))
                .thenReturn(JdbcTradeOutboxRepository.FailureResult.RETRY_SCHEDULED);
        when(repository.markPublished(retryLease.eventId(), retryLease.leaseToken(), NOW)).thenReturn(true);
        var publisher = new TradeOutboxPublisher(repository, transport, meters,
                Clock.fixed(NOW, ZoneOffset.UTC), "trade-test");

        assertThat(publisher.publishDue()).isZero();
        assertThat(publisher.publishDue()).isOne();
        verify(transport, times(2)).send(any(JdbcTradeOutboxRepository.OutboxEntity.class));
        assertThat(meters.counter("tidebid.outbox.publish", "service", "trade", "outcome", "failed")
                .count()).isEqualTo(1);
        assertThat(meters.counter("tidebid.outbox.publish", "service", "trade", "outcome", "published")
                .count()).isEqualTo(1);
    }

    @Test
    void returnsFailureUntilTheSynchronousBusinessHandlerCommits() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var transport = new TradeRocketMqTransport(properties(), List.of(), meters,
                mock(ClientServiceProvider.class));
        AtomicInteger attempts = new AtomicInteger();
        TradeRocketMqTransport.InboundHandler handler = new TradeRocketMqTransport.InboundHandler() {
            @Override
            public TradeRocketMqTransport.ConsumerBinding binding() {
                return new TradeRocketMqTransport.ConsumerBinding(
                        RocketMqTopology.TRADE_AUCTION_CONSUMER_GROUP,
                        Map.of(RocketMqTopology.AUCTION_EVENTS_TOPIC, "auction.closed-sold"));
            }

            @Override
            public void handle(TradeRocketMqTransport.InboundMessage message) {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("local transaction rolled back");
                }
            }
        };

        assertThat(transport.consumeSynchronously(handler, messageView(1))).isEqualTo(ConsumeResult.FAILURE);
        assertThat(transport.consumeSynchronously(handler, messageView(2))).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(attempts).hasValue(2);
        assertThat(meters.counter("tidebid.rocketmq.consume", "service", "trade",
                "consumerGroup", RocketMqTopology.TRADE_AUCTION_CONSUMER_GROUP,
                "outcome", "failure").count()).isEqualTo(1);
        assertThat(meters.counter("tidebid.rocketmq.consume", "service", "trade",
                "consumerGroup", RocketMqTopology.TRADE_AUCTION_CONSUMER_GROUP,
                "outcome", "success").count()).isEqualTo(1);
    }

    @Test
    void neverAcknowledgesAPermanentPoisonMessageEvenAtALateDeliveryAttempt() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var transport = new TradeRocketMqTransport(properties(), List.of(), meters,
                mock(ClientServiceProvider.class));
        TradeRocketMqTransport.InboundHandler poisonHandler = new TradeRocketMqTransport.InboundHandler() {
            @Override
            public TradeRocketMqTransport.ConsumerBinding binding() {
                return new TradeRocketMqTransport.ConsumerBinding(
                        RocketMqTopology.TRADE_AUCTION_CONSUMER_GROUP,
                        Map.of(RocketMqTopology.AUCTION_EVENTS_TOPIC, "auction.closed-sold"));
            }

            @Override
            public void handle(TradeRocketMqTransport.InboundMessage message) {
                throw new IllegalArgumentException("permanent invalid payload");
            }
        };

        assertThat(transport.consumeSynchronously(poisonHandler, messageView(17)))
                .isEqualTo(ConsumeResult.FAILURE);
        assertThat(meters.counter("tidebid.rocketmq.consume", "service", "trade",
                "consumerGroup", RocketMqTopology.TRADE_AUCTION_CONSUMER_GROUP,
                "outcome", "failure").count()).isEqualTo(1);
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
        var transport = new TradeRocketMqTransport(properties(), List.of(), new SimpleMeterRegistry(),
                provider, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(transport.buildMessage(outbox(
                "lease-overdue", RocketMqTopology.SCHEDULED_COMMANDS_TOPIC, NOW.minusSeconds(30))))
                .isSameAs(message);
        verify(builder).addProperty("payloadHash", EMPTY_JSON_SHA256);
        verify(builder).setDeliveryTimestamp(NOW.plusSeconds(1).toEpochMilli());
    }

    private static TradeRocketMqProperties properties() {
        return new TradeRocketMqProperties("127.0.0.1:8081", Duration.ofSeconds(3), 2,
                new TradeRocketMqProperties.Topics(
                        RocketMqTopology.TRADE_EVENTS_TOPIC,
                        RocketMqTopology.AUCTION_EVENTS_TOPIC,
                        RocketMqTopology.ACCOUNT_EVENTS_TOPIC,
                        RocketMqTopology.SCHEDULED_COMMANDS_TOPIC),
                new TradeRocketMqProperties.ConsumerGroups(
                        RocketMqTopology.TRADE_AUCTION_CONSUMER_GROUP,
                        RocketMqTopology.TRADE_ACCOUNT_CONSUMER_GROUP,
                        RocketMqTopology.TRADE_TIMEOUT_CONSUMER_GROUP));
    }

    private static MessageView messageView(int deliveryAttempt) {
        MessageView view = mock(MessageView.class);
        MessageId id = mock(MessageId.class);
        when(id.toString()).thenReturn("message-1");
        when(view.getMessageId()).thenReturn(id);
        when(view.getTopic()).thenReturn(RocketMqTopology.AUCTION_EVENTS_TOPIC);
        when(view.getBody()).thenReturn(ByteBuffer.wrap("{}".getBytes()));
        when(view.getProperties()).thenReturn(Map.of("eventId", "event-1"));
        when(view.getTag()).thenReturn(Optional.of("auction.closed-sold"));
        when(view.getKeys()).thenReturn(List.of("event-1"));
        when(view.getDeliveryAttempt()).thenReturn(deliveryAttempt);
        return view;
    }

    private static JdbcTradeOutboxRepository.OutboxEntity outbox(String leaseToken) {
        return outbox(leaseToken, RocketMqTopology.TRADE_EVENTS_TOPIC, NOW);
    }

    private static JdbcTradeOutboxRepository.OutboxEntity outbox(
            String leaseToken,
            String topic,
            Instant deliverAt
    ) {
        return new JdbcTradeOutboxRepository.OutboxEntity(
                1L, "event-1", "ORDER", "1001", "order.paid", 1,
                topic, "order.paid", "event-1",
                "{}", "a".repeat(64), deliverAt, "PUBLISHING", 0, NOW, "trade-test", leaseToken,
                NOW.plusSeconds(30), null, null, NOW, NOW);
    }
}

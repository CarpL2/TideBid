package io.github.carpl2.tidebid.auction.infrastructure.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionOutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-09-17T01:00:00Z");

    @Test
    void persistsBrokerAcknowledgementWithTheCurrentLease() {
        JdbcAuctionOutboxRepository repository = mock(JdbcAuctionOutboxRepository.class);
        AuctionRocketMqTransport transport = mock(AuctionRocketMqTransport.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var message = message("lease-1");
        when(repository.claimBatch("auction-test", NOW)).thenReturn(List.of(message));
        when(transport.send(message)).thenReturn("broker-message-1");
        when(repository.markPublished(message.eventId(), message.leaseToken(), NOW)).thenReturn(true);
        var publisher = new AuctionOutboxPublisher(repository, transport, meters,
                Clock.fixed(NOW, ZoneOffset.UTC), "auction-test");

        assertThat(publisher.publishDue()).isOne();
        assertThat(meters.counter("tidebid.outbox.publish", "service", "auction", "outcome", "published")
                .count()).isEqualTo(1);
        verify(repository).markPublished(message.eventId(), message.leaseToken(), NOW);
    }

    @Test
    void schedulesRepositoryBackoffWhenBrokerSendFails() {
        JdbcAuctionOutboxRepository repository = mock(JdbcAuctionOutboxRepository.class);
        AuctionRocketMqTransport transport = mock(AuctionRocketMqTransport.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var message = message("lease-2");
        when(repository.claimBatch("auction-test", NOW)).thenReturn(List.of(message));
        when(transport.send(message)).thenThrow(new IllegalStateException("broker unavailable"));
        when(repository.markFailed(message.eventId(), message.leaseToken(), "ILLEGALSTATEEXCEPTION", NOW))
                .thenReturn(JdbcAuctionOutboxRepository.FailureResult.RETRY_SCHEDULED);
        var publisher = new AuctionOutboxPublisher(repository, transport, meters,
                Clock.fixed(NOW, ZoneOffset.UTC), "auction-test");

        assertThat(publisher.publishDue()).isZero();
        assertThat(meters.counter("tidebid.outbox.publish", "service", "auction", "outcome", "failed")
                .count()).isEqualTo(1);
        verify(repository).markFailed(message.eventId(), message.leaseToken(), "ILLEGALSTATEEXCEPTION", NOW);
    }

    private static JdbcAuctionOutboxRepository.OutboxEntity message(String leaseToken) {
        return new JdbcAuctionOutboxRepository.OutboxEntity(
                1L, "event-1", "AUCTION", "1001", "auction.closed", 1,
                "tidebid-auction-events", "auction.closed", "event-1", "{}", "a".repeat(64),
                NOW, "PUBLISHING", 0, NOW, "auction-test", leaseToken,
                NOW.plusSeconds(30), null, null, NOW, NOW);
    }
}

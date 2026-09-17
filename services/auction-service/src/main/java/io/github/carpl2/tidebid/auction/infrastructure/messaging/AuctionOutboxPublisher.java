package io.github.carpl2.tidebid.auction.infrastructure.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Claims short Outbox leases and converts Broker acknowledgements into durable publication state. */
@Component
@Profile({"local-db", "nacos"})
public class AuctionOutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuctionOutboxPublisher.class);

    private final JdbcAuctionOutboxRepository repository;
    private final AuctionRocketMqTransport transport;
    private final Clock clock;
    private final String leaseOwner;
    private final Counter published;
    private final Counter failed;
    private final Counter staleLease;

    @Autowired
    public AuctionOutboxPublisher(
            JdbcAuctionOutboxRepository repository,
            AuctionRocketMqTransport transport,
            MeterRegistry meters
    ) {
        this(repository, transport, meters, Clock.systemUTC(), "auction-" + UUID.randomUUID());
    }

    AuctionOutboxPublisher(
            JdbcAuctionOutboxRepository repository,
            AuctionRocketMqTransport transport,
            MeterRegistry meters,
            Clock clock,
            String leaseOwner
    ) {
        this.repository = repository;
        this.transport = transport;
        this.clock = clock;
        this.leaseOwner = leaseOwner;
        this.published = meters.counter("tidebid.outbox.publish", "service", "auction", "outcome", "published");
        this.failed = meters.counter("tidebid.outbox.publish", "service", "auction", "outcome", "failed");
        this.staleLease = meters.counter("tidebid.outbox.publish", "service", "auction", "outcome", "stale_lease");
    }

    @Scheduled(
            initialDelayString = "${tidebid.messaging.outbox.scan-interval:1s}",
            fixedDelayString = "${tidebid.messaging.outbox.scan-interval:1s}"
    )
    public int publishDue() {
        Instant claimedAt = clock.instant();
        List<JdbcAuctionOutboxRepository.OutboxEntity> batch = repository.claimBatch(leaseOwner, claimedAt);
        int acknowledgements = 0;
        for (JdbcAuctionOutboxRepository.OutboxEntity outbox : batch) {
            try {
                String brokerMessageId = transport.send(outbox);
                if (repository.markPublished(outbox.eventId(), outbox.leaseToken(), clock.instant())) {
                    acknowledgements++;
                    published.increment();
                    LOGGER.info("Outbox published: service=auction, eventId={}, topic={}, brokerMessageId={}",
                            outbox.eventId(), outbox.topic(), brokerMessageId);
                } else {
                    staleLease.increment();
                    LOGGER.warn("Outbox ACK ignored after lease loss: service=auction, eventId={}", outbox.eventId());
                }
            } catch (RuntimeException exception) {
                failed.increment();
                markFailed(outbox, exception);
            }
        }
        return acknowledgements;
    }

    private void markFailed(JdbcAuctionOutboxRepository.OutboxEntity outbox, RuntimeException exception) {
        String errorCode = errorCode(exception);
        try {
            JdbcAuctionOutboxRepository.FailureResult result = repository.markFailed(
                    outbox.eventId(), outbox.leaseToken(), errorCode, clock.instant());
            if (result == JdbcAuctionOutboxRepository.FailureResult.STALE_LEASE) {
                staleLease.increment();
            }
            LOGGER.warn("Outbox publish failed: service=auction, eventId={}, topic={}, result={}, errorCode={}",
                    outbox.eventId(), outbox.topic(), result, errorCode);
        } catch (RuntimeException persistenceFailure) {
            LOGGER.error("Outbox failure result could not be persisted: service=auction, eventId={}, errorCode={}",
                    outbox.eventId(), errorCode(persistenceFailure));
        }
    }

    private static String errorCode(Throwable exception) {
        String code = exception.getClass().getSimpleName()
                .replaceAll("[^A-Za-z0-9_]", "_")
                .toUpperCase(Locale.ROOT);
        if (code.isBlank()) {
            return "UNKNOWN";
        }
        return code.length() <= 96 ? code : code.substring(0, 96);
    }
}

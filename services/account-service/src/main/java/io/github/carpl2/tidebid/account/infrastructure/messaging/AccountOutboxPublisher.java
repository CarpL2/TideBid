package io.github.carpl2.tidebid.account.infrastructure.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
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
import java.util.concurrent.atomic.AtomicLong;

@Component
@Profile({"local-db", "nacos"})
public class AccountOutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountOutboxPublisher.class);
    private final JdbcAccountOutboxRepository repository;
    private final AccountRocketMqTransport transport;
    private final Clock clock;
    private final String leaseOwner;
    private final Counter published;
    private final Counter failed;
    private final Counter staleLease;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong deadMessages = new AtomicLong();
    private final AtomicLong oldestDueAgeSeconds = new AtomicLong();

    @Autowired
    public AccountOutboxPublisher(JdbcAccountOutboxRepository repository,
                                  AccountRocketMqTransport transport, MeterRegistry meters) {
        this(repository, transport, meters, Clock.systemUTC(), "account-" + UUID.randomUUID());
    }

    AccountOutboxPublisher(JdbcAccountOutboxRepository repository, AccountRocketMqTransport transport,
                           MeterRegistry meters, Clock clock, String leaseOwner) {
        this.repository = repository;
        this.transport = transport;
        this.clock = clock;
        this.leaseOwner = leaseOwner;
        this.published = meters.counter("tidebid.outbox.publish", "service", "account", "outcome", "published");
        this.failed = meters.counter("tidebid.outbox.publish", "service", "account", "outcome", "failed");
        this.staleLease = meters.counter("tidebid.outbox.publish", "service", "account", "outcome", "stale_lease");
        Gauge.builder("tidebid.outbox.backlog", backlog, AtomicLong::get).tag("service", "account").register(meters);
        Gauge.builder("tidebid.outbox.dead", deadMessages, AtomicLong::get).tag("service", "account").register(meters);
        Gauge.builder("tidebid.outbox.oldest.due.age", oldestDueAgeSeconds, AtomicLong::get)
                .tag("service", "account").baseUnit("seconds").register(meters);
    }

    @Scheduled(initialDelayString = "${tidebid.messaging.outbox.scan-interval:1s}",
            fixedDelayString = "${tidebid.messaging.outbox.scan-interval:1s}")
    public int publishDue() {
        List<JdbcAccountOutboxRepository.OutboxEntity> batch = repository.claimBatch(leaseOwner, clock.instant());
        int acknowledgements = 0;
        for (JdbcAccountOutboxRepository.OutboxEntity outbox : batch) {
            try {
                String brokerMessageId = transport.send(outbox);
                if (repository.markPublished(outbox.eventId(), outbox.leaseToken(), clock.instant())) {
                    acknowledgements++;
                    published.increment();
                    LOGGER.info("Outbox published: service=account, eventId={}, topic={}, brokerMessageId={}",
                            outbox.eventId(), outbox.topic(), brokerMessageId);
                } else {
                    staleLease.increment();
                    LOGGER.warn("Outbox ACK ignored after lease loss: service=account, eventId={}", outbox.eventId());
                }
            } catch (RuntimeException exception) {
                failed.increment();
                markFailed(outbox, exception);
            }
        }
        observeBacklog();
        return acknowledgements;
    }

    private void observeBacklog() {
        JdbcAccountOutboxRepository.OutboxDiagnostics diagnostics = repository.diagnostics(clock.instant());
        backlog.set(diagnostics.backlog());
        deadMessages.set(diagnostics.deadMessages());
        oldestDueAgeSeconds.set(diagnostics.oldestDueAgeSeconds());
        if (diagnostics.deadMessages() > 0 || diagnostics.oldestDueAgeSeconds() > 0) {
            LOGGER.debug("Outbox backlog observed: service=account, backlog={}, deadMessages={}, oldestDueAgeSeconds={}",
                    diagnostics.backlog(), diagnostics.deadMessages(), diagnostics.oldestDueAgeSeconds());
        }
    }

    private void markFailed(JdbcAccountOutboxRepository.OutboxEntity outbox, RuntimeException exception) {
        String code = errorCode(exception);
        try {
            JdbcAccountOutboxRepository.FailureResult result = repository.markFailed(
                    outbox.eventId(), outbox.leaseToken(), code, clock.instant());
            if (result == JdbcAccountOutboxRepository.FailureResult.STALE_LEASE) {
                staleLease.increment();
            }
            LOGGER.warn("Outbox publish failed: service=account, eventId={}, topic={}, result={}, errorCode={}",
                    outbox.eventId(), outbox.topic(), result, code);
        } catch (RuntimeException persistenceFailure) {
            LOGGER.error("Outbox failure result could not be persisted: service=account, eventId={}, errorCode={}",
                    outbox.eventId(), errorCode(persistenceFailure));
        }
    }

    private static String errorCode(Throwable exception) {
        String code = exception.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "_")
                .toUpperCase(Locale.ROOT);
        if (code.isBlank()) return "UNKNOWN";
        return code.length() <= 96 ? code : code.substring(0, 96);
    }
}

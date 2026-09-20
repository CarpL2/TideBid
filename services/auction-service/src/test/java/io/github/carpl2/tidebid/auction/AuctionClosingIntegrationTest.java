package io.github.carpl2.tidebid.auction;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.auction.application.AuctionClosingService;
import io.github.carpl2.tidebid.auction.application.port.AuctionClosingTransaction.CloseResult;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionRocketMqProperties;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.AuctionCloseCommandHandler;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.AuctionOutboxEventFactory;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.AuctionRocketMqTransport;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.JdbcAuctionOutboxRepository;
import io.github.carpl2.tidebid.auction.infrastructure.scheduling.AuctionDueClosingJob;
import io.github.carpl2.tidebid.contracts.AuctionClosedSoldEvent;
import io.github.carpl2.tidebid.contracts.AuctionClosedUnsoldEvent;
import io.github.carpl2.tidebid.contracts.CloseAuctionCommand;
import io.github.carpl2.tidebid.contracts.DepositSettlementRequestedEvent;
import io.github.carpl2.tidebid.contracts.DepositSettlementType;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "tidebid.auction.account-client.internal-token=test-internal-token-with-at-least-32-characters",
                "tidebid.auction.storage.enabled=false",
                "tidebid.auction.timing.opening-scan-enabled=false",
                "tidebid.auction.registration-recovery.enabled=false",
                "tidebid.auction.close-scheduling.enabled=true",
                "tidebid.auction.close-scheduling.scan-interval=5m",
                "tidebid.auction.close-scheduling.batch-size=1",
                "tidebid.scheduling.enabled=false"
        }
)
@ActiveProfiles("local-db")
@EnabledIfEnvironmentVariable(named = "TIDEBID_AUCTION_DB_PASSWORD", matches = ".+")
class AuctionClosingIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuctionClosingService closingService;
    @Autowired private AuctionDueClosingJob dueClosingJob;
    @Autowired private AuctionCloseCommandHandler closeCommandHandler;
    @Autowired private AuctionRocketMqProperties rocketMqProperties;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuctionOutboxEventFactory eventFactory;
    @Autowired private JdbcAuctionOutboxRepository outboxRepository;

    @Test
    void soldCloseAtomicallyPersistsWinnerAndOnlyReleasesLosers() {
        Fixture fixture = soldFixture();
        try {
            CloseResult result = closingService.fromDatabaseScan(fixture.auctionId(), fixture.endAt());

            assertThat(result).isEqualTo(CloseResult.CLOSED_SOLD);
            assertThat(state(fixture.auctionId())).containsExactly(
                    "CLOSED_SOLD", fixture.winnerId(), fixture.bidId(), new BigDecimal("120.00"));
            assertThat(countEvents(fixture.auctionId(), AuctionClosedSoldEvent.EVENT_TYPE)).isOne();
            assertThat(countEvents(fixture.auctionId(), DepositSettlementRequestedEvent.EVENT_TYPE)).isOne();
            assertThat(eventPayloads(fixture.auctionId(), DepositSettlementRequestedEvent.EVENT_TYPE).getFirst())
                    .contains(fixture.loserHoldNo())
                    .doesNotContain(fixture.winnerHoldNo());
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void unsoldCloseReleasesEveryRegisteredDepositAndScannerUsesSameService() {
        Fixture fixture = unsoldFixture(2, Instant.parse("2000-01-01T00:00:00Z"));
        try {
            assertThat(dueClosingJob.closeDueAuctions()).isPositive();

            assertThat(state(fixture.auctionId()).getFirst()).isEqualTo("CLOSED_UNSOLD");
            assertThat(countEvents(fixture.auctionId(), AuctionClosedUnsoldEvent.EVENT_TYPE)).isOne();
            assertThat(countEvents(fixture.auctionId(), DepositSettlementRequestedEvent.EVENT_TYPE)).isEqualTo(2);
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void earlyAndStaleCommandsNeverCloseAndLeaveACorrectFutureCommand() {
        Fixture fixture = unsoldFixture(0, Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(120));
        UUID earlyEventId = UUID.randomUUID();
        try {
            assertThat(closingService.fromMessage(
                    fixture.auctionId(), fixture.endAt(), earlyEventId, "early-close-test"))
                    .isEqualTo(CloseResult.TOO_EARLY);
            assertThat(state(fixture.auctionId()).getFirst()).isEqualTo("AWAITING_CLOSE");
            assertThat(countEvents(fixture.auctionId(), CloseAuctionCommand.EVENT_TYPE)).isOne();
            Timestamp deliverAt = jdbc.queryForObject("""
                    SELECT deliver_at FROM auction_outbox
                    WHERE aggregate_id = ? AND event_type = ?
                    """, Timestamp.class, Long.toString(fixture.auctionId()), CloseAuctionCommand.EVENT_TYPE);
            assertThat(deliverAt.toInstant()).isEqualTo(fixture.endAt());

            assertThat(closingService.fromMessage(
                    fixture.auctionId(), fixture.endAt().minusSeconds(1), UUID.randomUUID(), "stale-close-test"))
                    .isEqualTo(CloseResult.END_TIME_CHANGED);
            assertThat(state(fixture.auctionId()).getFirst()).isEqualTo("AWAITING_CLOSE");
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void closeCommandConsumerValidatesAndDeduplicatesTheEnvelope() throws Exception {
        Fixture fixture = unsoldFixture(0, Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(120));
        UUID eventId = UUID.randomUUID();
        EventEnvelope<CloseAuctionCommand> envelope = new EventEnvelope<>(
                eventId,
                CloseAuctionCommand.EVENT_TYPE,
                CloseAuctionCommand.SCHEMA_VERSION,
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                "test-producer",
                "consumer-close-test",
                new CloseAuctionCommand(fixture.auctionId(), fixture.endAt()));
        byte[] body = objectMapper.writeValueAsBytes(envelope);
        Map<String, String> metadata = Map.of(
                "eventId", eventId.toString(),
                "eventType", CloseAuctionCommand.EVENT_TYPE,
                "schemaVersion", Integer.toString(CloseAuctionCommand.SCHEMA_VERSION),
                "payloadHash", sha256(body));
        AuctionRocketMqTransport.InboundMessage message = new AuctionRocketMqTransport.InboundMessage(
                "broker-message-test",
                rocketMqProperties.topics().scheduledCommands(),
                CloseAuctionCommand.EVENT_TYPE,
                List.of(eventId.toString()),
                metadata,
                body,
                1);
        try {
            closeCommandHandler.handle(message);
            closeCommandHandler.handle(message);

            Long inboxCount = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM auction_inbox WHERE consumer_name = ? AND event_id = ?
                    """, Long.class, rocketMqProperties.consumerGroups().closeAuction(), eventId.toString());
            assertThat(inboxCount).isOne();
            assertThat(countEvents(fixture.auctionId(), CloseAuctionCommand.EVENT_TYPE)).isOne();
            assertThat(state(fixture.auctionId()).getFirst()).isEqualTo("AWAITING_CLOSE");
        } finally {
            jdbc.update("DELETE FROM auction_inbox WHERE consumer_name = ? AND event_id = ?",
                    rocketMqProperties.consumerGroups().closeAuction(), eventId.toString());
            cleanup(fixture);
        }
    }

    @Test
    void concurrentTriggersProduceOnlyOneTerminalTransitionAndOneResultEvent() throws Exception {
        Fixture fixture = soldFixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> closeConcurrently(ready, start, fixture));
            var second = executor.submit(() -> closeConcurrently(ready, start, fixture));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            CloseResult firstResult = first.get(10, TimeUnit.SECONDS);
            CloseResult secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(java.util.List.of(firstResult, secondResult)).contains(CloseResult.CLOSED_SOLD);
            assertThat(java.util.List.of(firstResult, secondResult))
                    .allMatch(result -> result == CloseResult.CLOSED_SOLD
                            || result == CloseResult.ALREADY_CLOSED
                            || result == CloseResult.LOST_RACE);
            assertThat(countEvents(fixture.auctionId(), AuctionClosedSoldEvent.EVENT_TYPE)).isOne();
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void anyOutboxConflictRollsBackTheTerminalTransitionAndEarlierResultEvent() {
        Fixture fixture = soldFixture();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        DepositSettlementRequestedEvent conflictingRelease = new DepositSettlementRequestedEvent(
                DepositSettlementType.RELEASE,
                fixture.auctionId(),
                null,
                fixture.loserId(),
                fixture.loserHoldNo(),
                new BigDecimal("50.00"),
                new BigDecimal("0.00"));
        try {
            outboxRepository.enqueue(eventFactory.depositRelease(conflictingRelease, now, null), now);

            assertThatThrownBy(() -> closingService.fromDatabaseScan(fixture.auctionId(), fixture.endAt()))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

            assertThat(state(fixture.auctionId()).getFirst()).isEqualTo("AWAITING_CLOSE");
            assertThat(countEvents(fixture.auctionId(), AuctionClosedSoldEvent.EVENT_TYPE)).isZero();
            assertThat(countEvents(fixture.auctionId(), DepositSettlementRequestedEvent.EVENT_TYPE)).isOne();
        } finally {
            cleanup(fixture);
        }
    }

    private CloseResult closeConcurrently(CountDownLatch ready, CountDownLatch start, Fixture fixture) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return closingService.fromDatabaseScan(fixture.auctionId(), fixture.endAt());
    }

    private Fixture soldFixture() {
        Instant endAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(1);
        Fixture fixture = baseFixture(endAt);
        insertRegistration(fixture.auctionId(), fixture.winnerId(), fixture.winnerHoldNo());
        insertRegistration(fixture.auctionId(), fixture.loserId(), fixture.loserHoldNo());
        jdbc.update("""
                INSERT INTO bid_record
                    (id, auction_id, bidder_id, request_id, amount, previous_price, sequence_no, created_at)
                VALUES (?, ?, ?, ?, 120.00, NULL, 1, ?)
                """, fixture.bidId(), fixture.auctionId(), fixture.winnerId(), "close-bid-" + fixture.bidId(),
                timestamp(endAt.minusSeconds(10)));
        jdbc.update("""
                UPDATE auction_session
                SET current_price = 120.00, current_bidder_id = ?, bid_count = 1
                WHERE id = ?
                """, fixture.winnerId(), fixture.auctionId());
        return fixture;
    }

    private Fixture unsoldFixture(int registrations) {
        return unsoldFixture(registrations, Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(1));
    }

    private Fixture unsoldFixture(int registrations, Instant endAt) {
        Fixture fixture = baseFixture(endAt);
        if (registrations > 0) {
            insertRegistration(fixture.auctionId(), fixture.winnerId(), fixture.winnerHoldNo());
        }
        if (registrations > 1) {
            insertRegistration(fixture.auctionId(), fixture.loserId(), fixture.loserHoldNo());
        }
        return fixture;
    }

    private Fixture baseFixture(Instant endAt) {
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long sellerId = IdWorker.getId();
        long winnerId = IdWorker.getId();
        long loserId = IdWorker.getId();
        long bidId = IdWorker.getId();
        Instant createdAt = endAt.minusSeconds(3600);
        jdbc.update("""
                INSERT INTO auction_item
                    (id, seller_id, title, description, category, item_condition, review_status,
                     submission_version, version, submitted_at, approved_at, created_at, updated_at)
                VALUES (?, ?, 'Reliable close item', 'Integration fixture for reliable auction closing',
                        'ELECTRONICS', 'GOOD', 'APPROVED', 1, 1, ?, ?, ?, ?)
                """, itemId, sellerId, timestamp(createdAt), timestamp(createdAt), timestamp(createdAt),
                timestamp(createdAt));
        jdbc.update("""
                INSERT INTO auction_session
                    (id, item_id, seller_id, start_price, bid_increment, deposit_amount,
                     current_price, current_bidder_id, bid_count, start_at, end_at, original_end_at, status,
                     version, created_at, updated_at)
                VALUES (?, ?, ?, 100.00, 10.00, 50.00, NULL, NULL, ?, ?, ?, ?, 'AWAITING_CLOSE', 3, ?, ?)
                """, auctionId, itemId, sellerId, 0L, timestamp(createdAt.plusSeconds(60)),
                timestamp(endAt), timestamp(endAt), timestamp(createdAt), timestamp(endAt));
        return new Fixture(itemId, auctionId, sellerId, winnerId, loserId, bidId, endAt,
                "REGISTRATION:" + auctionId + ":winner", "REGISTRATION:" + auctionId + ":loser");
    }

    private void insertRegistration(long auctionId, long bidderId, String holdNo) {
        long id = IdWorker.getId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO auction_registration
                    (id, registration_no, auction_id, bidder_id, deposit_amount, status, failure_code,
                     attempt_count, next_retry_at, last_attempt_at, lease_owner, lease_until,
                     registered_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 50.00, 'REGISTERED', NULL, 1, NULL, ?, NULL, NULL, ?, 1, ?, ?)
                """, id, holdNo, auctionId, bidderId, timestamp(now), timestamp(now), timestamp(now), timestamp(now));
    }

    private java.util.List<Object> state(long auctionId) {
        return jdbc.queryForObject("""
                SELECT status, winner_id, winning_bid_id, final_price
                FROM auction_session WHERE id = ?
                """, (row, rowNumber) -> java.util.List.of(
                        row.getString("status"),
                        row.getObject("winner_id") == null ? 0L : row.getLong("winner_id"),
                        row.getObject("winning_bid_id") == null ? 0L : row.getLong("winning_bid_id"),
                        row.getBigDecimal("final_price") == null ? BigDecimal.ZERO : row.getBigDecimal("final_price")),
                auctionId);
    }

    private long countEvents(long auctionId, String eventType) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM auction_outbox WHERE aggregate_id = ? AND event_type = ?
                """, Long.class, Long.toString(auctionId), eventType);
        return count == null ? 0 : count;
    }

    private java.util.List<String> eventPayloads(long auctionId, String eventType) {
        return jdbc.queryForList("""
                SELECT CAST(payload AS CHAR) FROM auction_outbox WHERE aggregate_id = ? AND event_type = ?
                """, String.class, Long.toString(auctionId), eventType);
    }

    private void cleanup(Fixture fixture) {
        jdbc.update("DELETE FROM auction_outbox WHERE aggregate_id = ?", Long.toString(fixture.auctionId()));
        jdbc.update("DELETE FROM auction_registration WHERE auction_id = ?", fixture.auctionId());
        jdbc.update("""
                UPDATE auction_session
                SET status = 'AWAITING_CLOSE', winner_id = NULL, winning_bid_id = NULL,
                    final_price = NULL, closed_at = NULL
                WHERE id = ?
                """, fixture.auctionId());
        jdbc.update("DELETE FROM bid_record WHERE auction_id = ?", fixture.auctionId());
        jdbc.update("DELETE FROM auction_session WHERE id = ?", fixture.auctionId());
        jdbc.update("DELETE FROM auction_item WHERE id = ?", fixture.itemId());
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record Fixture(
            long itemId,
            long auctionId,
            long sellerId,
            long winnerId,
            long loserId,
            long bidId,
            Instant endAt,
            String winnerHoldNo,
            String loserHoldNo
    ) { }
}

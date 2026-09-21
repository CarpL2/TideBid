package io.github.carpl2.tidebid.auction;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.carpl2.tidebid.auction.application.port.AuctionBidCommandRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionBidCommandTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionClosingTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionProxyBidRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommand;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandPlanner;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandType;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBid;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBidStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.auction.domain.BidSource;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.JdbcAuctionOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against the real Auction MySQL schema when TIDEBID_AUCTION_DB_PASSWORD is configured.
 * The tests use generated ids and remove only their own rows.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "tidebid.auction.account-client.internal-token=test-internal-token-with-at-least-32-characters",
                "tidebid.auction.storage.enabled=false",
                "tidebid.auction.timing.opening-scan-enabled=false",
                "tidebid.auction.registration-recovery.enabled=false",
                "tidebid.auction.close-scheduling.scan-interval=5m",
                "tidebid.scheduling.enabled=false"
        }
)
@ActiveProfiles("local-db")
@EnabledIfEnvironmentVariable(named = "TIDEBID_AUCTION_DB_PASSWORD", matches = ".+")
class AuctionProxyMySqlIntegrationTest {

    @Autowired private AuctionItemRepository itemRepository;
    @Autowired private AuctionSessionRepository sessionRepository;
    @Autowired private AuctionProxyBidRepository proxyBidRepository;
    @Autowired private AuctionBidCommandRepository commandRepository;
    @Autowired private AuctionBidCommandTransaction commandTransaction;
    @Autowired private AuctionClosingTransaction closingTransaction;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    @Test
    void manualBidAgainstProxyPersistsTwoPublicBidsAndNeverStoresMaximumInOutboxPayload() {
        Fixture fixture = fixture();
        try {
            AuctionProxyBid defender = proxy(fixture.auctionId(), 201L, "300.00", 1L);
            proxyBidRepository.insert(defender);
            AuctionSession session = sessionRepository.findSessionById(fixture.auctionId()).orElseThrow();
            AuctionBidCommandPlanner.Plan plan = new AuctionBidCommandPlanner().plan(
                    session,
                    List.of(defender),
                    AuctionBidCommandPlanner.Command.manualBid(101L, new BigDecimal("200.00"))
            );

            commit(fixture.auctionId(), 101L, AuctionBidCommandType.MANUAL_BID, plan, null);

            AuctionSession stored = sessionRepository.findSessionById(fixture.auctionId()).orElseThrow();
            assertThat(stored.currentBidderId()).isEqualTo(201L);
            assertThat(stored.currentPrice()).isEqualByComparingTo("210.00");
            assertThat(stored.bidCount()).isEqualTo(2L);
            List<String> sources = jdbc.queryForList(
                    "SELECT source FROM bid_record WHERE auction_id = ? ORDER BY sequence_no",
                    String.class, fixture.auctionId());
            assertThat(sources).containsExactly("MANUAL", "PROXY");
            List<String> outboxPayloads = jdbc.queryForList(
                    "SELECT CAST(payload AS CHAR) FROM auction_outbox WHERE aggregate_id = ?",
                    String.class, Long.toString(fixture.auctionId()));
            assertThat(outboxPayloads).hasSize(2);
            assertThat(outboxPayloads).allSatisfy(payload -> {
                assertThat(payload).doesNotContain("300.00");
                assertThat(payload).doesNotContain("maxAmount");
            });
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void twoPersistedProxyRulesProduceOnlyTheNecessaryPublicPrice() {
        Fixture fixture = fixture();
        try {
            AuctionProxyBid earlier = proxy(fixture.auctionId(), 201L, "300.00", 1L);
            AuctionProxyBid challenger = proxy(fixture.auctionId(), 101L, "500.00", 2L);
            proxyBidRepository.insert(earlier);
            AuctionSession session = sessionRepository.findSessionById(fixture.auctionId()).orElseThrow();
            AuctionBidCommandPlanner.Plan plan = new AuctionBidCommandPlanner().plan(
                    session,
                    List.of(earlier, challenger),
                    AuctionBidCommandPlanner.Command.proxyRuleChanged(
                            AuctionBidCommandType.UPSERT_PROXY, 101L)
            );

            commit(fixture.auctionId(), 101L, AuctionBidCommandType.UPSERT_PROXY, plan,
                    AuctionBidCommandTransaction.ProxyMutation.insert(challenger));

            AuctionSession stored = sessionRepository.findSessionById(fixture.auctionId()).orElseThrow();
            assertThat(stored.currentBidderId()).isEqualTo(101L);
            assertThat(stored.currentPrice()).isEqualByComparingTo("310.00");
            assertThat(proxyBidRepository.findActiveByAuction(fixture.auctionId()))
                    .extracting(AuctionProxyBid::bidderId)
                    .containsExactlyInAnyOrder(201L, 101L);
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void concurrentEqualManualBidsAcceptOnlyOneSequence() throws Exception {
        Fixture fixture = fixture();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            AuctionSession session = sessionRepository.findSessionById(fixture.auctionId()).orElseThrow();
            AuctionBidCommandPlanner planner = new AuctionBidCommandPlanner();
            AuctionBidCommandPlanner.Plan firstPlan = planner.plan(
                    session, List.of(), AuctionBidCommandPlanner.Command.manualBid(101L, new BigDecimal("150.00")));
            AuctionBidCommandPlanner.Plan secondPlan = planner.plan(
                    session, List.of(), AuctionBidCommandPlanner.Command.manualBid(102L, new BigDecimal("150.00")));
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<String> first = executor.submit(() -> commitConcurrently(
                    ready, start, fixture.auctionId(), 101L, AuctionBidCommandType.MANUAL_BID, firstPlan));
            Future<String> second = executor.submit(() -> commitConcurrently(
                    ready, start, fixture.auctionId(), 102L, AuctionBidCommandType.MANUAL_BID, secondPlan));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "CONFLICT");

            AuctionSession stored = sessionRepository.findSessionById(fixture.auctionId()).orElseThrow();
            assertThat(stored.bidCount()).isEqualTo(1L);
            assertThat(stored.currentPrice()).isEqualByComparingTo("150.00");
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void antiSnipingMakesOldCloseCommandStaleAndNewCommandClosesOnlyOnce() {
        Fixture fixture = fixtureEndingSoon();
        Instant originalEndAt = sessionRepository.findSessionById(fixture.auctionId()).orElseThrow().endAt();
        try {
            AuctionSession session = sessionRepository.findSessionById(fixture.auctionId()).orElseThrow();
            AuctionBidCommandPlanner.Plan plan = new AuctionBidCommandPlanner().plan(
                    session, List.of(),
                    AuctionBidCommandPlanner.Command.manualBid(101L, new BigDecimal("150.00")));
            commit(fixture.auctionId(), 101L, AuctionBidCommandType.MANUAL_BID, plan, null);

            AuctionSession extended = sessionRepository.findSessionById(fixture.auctionId()).orElseThrow();
            assertThat(extended.endAt()).isAfterOrEqualTo(originalEndAt.plusSeconds(30));
            assertThat(extended.endAt()).isBefore(originalEndAt.plusSeconds(60));
            assertThat(extended.extensionCount()).isEqualTo(1);
            assertThat(countOutbox(fixture.auctionId(), "auction.bid-accepted")).isOne();
            assertThat(countOutbox(fixture.auctionId(), "auction.time-extended")).isOne();
            assertThat(countOutbox(fixture.auctionId(), "auction.close")).isOne();
            assertThat(outboxPayloads(fixture.auctionId(), "auction.close").getFirst())
                    .contains(extended.endAt().toString());

            AuctionClosingTransaction.CloseResult stale = closingTransaction.close(
                    new AuctionClosingTransaction.CloseCommand(
                            fixture.auctionId(), originalEndAt, extended.endAt().plusSeconds(1),
                            AuctionClosingTransaction.TriggerSource.MESSAGE, UUID.randomUUID(), "stale-close"));
            assertThat(stale).isEqualTo(AuctionClosingTransaction.CloseResult.END_TIME_CHANGED);
            assertThat(sessionRepository.findSessionById(fixture.auctionId()).orElseThrow().status())
                    .isEqualTo(AuctionSessionStatus.OPEN);

            AuctionClosingTransaction.CloseResult firstClose = closingTransaction.close(
                    new AuctionClosingTransaction.CloseCommand(
                            fixture.auctionId(), extended.endAt(), extended.endAt().plusSeconds(1),
                            AuctionClosingTransaction.TriggerSource.MESSAGE, UUID.randomUUID(), "new-close"));
            assertThat(firstClose).isEqualTo(AuctionClosingTransaction.CloseResult.CLOSED_SOLD);
            AuctionClosingTransaction.CloseResult duplicateClose = closingTransaction.close(
                    new AuctionClosingTransaction.CloseCommand(
                            fixture.auctionId(), extended.endAt(), extended.endAt().plusSeconds(2),
                            AuctionClosingTransaction.TriggerSource.DATABASE_SCAN, null, null));
            assertThat(duplicateClose).isEqualTo(AuctionClosingTransaction.CloseResult.ALREADY_CLOSED);
            assertThat(countOutbox(fixture.auctionId(), "auction.closed-sold")).isOne();
        } finally {
            cleanup(fixture);
        }
    }

    private String commitConcurrently(
            CountDownLatch ready,
            CountDownLatch start,
            long auctionId,
            long actorId,
            AuctionBidCommandType type,
            AuctionBidCommandPlanner.Plan plan
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent commit start timed out");
        }
        try {
            commit(auctionId, actorId, type, plan, null);
            return "SUCCESS";
        } catch (AuctionBidCommandTransaction.BidConflictException exception) {
            return "CONFLICT";
        }
    }

    private AuctionBidCommandTransaction.CommittedCommand commit(
            long auctionId,
            long actorId,
            AuctionBidCommandType type,
            AuctionBidCommandPlanner.Plan plan,
            AuctionBidCommandTransaction.ProxyMutation mutation
    ) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String requestId = "mysql_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String payloadHash = "a".repeat(64);
        long commandId = IdWorker.getId();
        AuctionBidCommand processing = new AuctionBidCommand(
                commandId, auctionId, actorId, requestId, type, payloadHash,
                AuctionBidCommandStatus.PROCESSING, null, null, null, null, null, now, null
        );
        List<BidRecord> bids = plan.bids().stream().map(bid -> new BidRecord(
                IdWorker.getId(), auctionId, bid.bidderId(), requestId, bid.source(), commandId,
                bid.amount(), bid.previousPrice(), bid.sequenceNo(), now
        )).toList();
        AuctionBidCommand completed = new AuctionBidCommand(
                commandId, auctionId, actorId, requestId, type, payloadHash,
                AuctionBidCommandStatus.SUCCEEDED, bids.size(), plan.displayPrice(),
                actorId == plan.leadingBidderId(), bids.isEmpty() ? null : bids.getFirst().sequenceNo(),
                bids.isEmpty() ? null : bids.getLast().sequenceNo(), now, now
        );
        AuctionSession session = sessionRepository.findSessionById(auctionId).orElseThrow();
        return commandTransaction.commit(new AuctionBidCommandTransaction.CommitRequest(
                processing, completed, mutation, bids, session.version()
        ));
    }

    private Fixture fixture() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long sellerId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        itemRepository.insertItem(new AuctionItem(
                itemId, sellerId, "Proxy integration item", "Proxy integration item description", "OTHER",
                AuctionItemCondition.GOOD, AuctionItemReviewStatus.APPROVED, 1, 2,
                now.minusSeconds(120), now.minusSeconds(60), now.minusSeconds(180), now.minusSeconds(60)
        ));
        sessionRepository.insertSession(new AuctionSession(
                auctionId, itemId, sellerId,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0, now.minusSeconds(60), now.plusSeconds(3600),
                AuctionSessionStatus.OPEN, 0L, now.minusSeconds(180), now.minusSeconds(60)
        ));
        return new Fixture(itemId, auctionId);
    }

    private Fixture fixtureEndingSoon() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long sellerId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long buyerId = 101L;
        itemRepository.insertItem(new AuctionItem(
                itemId, sellerId, "Anti-sniping integration item", "Anti-sniping integration item description", "OTHER",
                AuctionItemCondition.GOOD, AuctionItemReviewStatus.APPROVED, 1, 2,
                now.minusSeconds(120), now.minusSeconds(60), now.minusSeconds(180), now.minusSeconds(60)
        ));
        sessionRepository.insertSession(new AuctionSession(
                auctionId, itemId, sellerId,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0, now.minusSeconds(60), now.plusSeconds(30),
                AuctionSessionStatus.OPEN, 0L, now.minusSeconds(180), now.minusSeconds(60)
        ));
        long registrationId = IdWorker.getId();
        String holdNo = "ANTI-SNIPING-" + registrationId;
        jdbc.update("""
                INSERT INTO auction_registration
                    (id, registration_no, auction_id, bidder_id, deposit_amount, status, failure_code,
                     attempt_count, next_retry_at, last_attempt_at, lease_owner, lease_until,
                     registered_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 50.00, 'REGISTERED', NULL, 1, NULL, ?, NULL, NULL, ?, 1, ?, ?)
                """, registrationId, holdNo, auctionId, buyerId,
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        return new Fixture(itemId, auctionId);
    }

    private AuctionProxyBid proxy(long auctionId, long bidderId, String maximum, long priority) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return new AuctionProxyBid(
                IdWorker.getId(), auctionId, bidderId, new BigDecimal(maximum),
                AuctionProxyBidStatus.ACTIVE, priority, 0L, null, now, now
        );
    }

    private void cleanup(Fixture fixture) {
        String auctionId = Long.toString(fixture.auctionId());
        jdbc.update("""
                UPDATE auction_session
                SET status = 'OPEN', winner_id = NULL, winning_bid_id = NULL,
                    final_price = NULL, closed_at = NULL
                WHERE id = ?
                """, fixture.auctionId());
        jdbc.update("DELETE FROM bid_record WHERE auction_id = ?", fixture.auctionId());
        jdbc.update("DELETE FROM auction_outbox WHERE aggregate_id = ?", auctionId);
        jdbc.update("DELETE FROM auction_bid_command WHERE auction_id = ?", fixture.auctionId());
        jdbc.update("DELETE FROM auction_proxy_bid WHERE auction_id = ?", fixture.auctionId());
        jdbc.update("DELETE FROM auction_registration WHERE auction_id = ?", fixture.auctionId());
        jdbc.update("DELETE FROM auction_session WHERE id = ?", fixture.auctionId());
        jdbc.update("DELETE FROM auction_item WHERE id = ?", fixture.itemId());
    }

    private long countOutbox(long auctionId, String eventType) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auction_outbox WHERE aggregate_id = ? AND event_type = ?",
                Long.class, Long.toString(auctionId), eventType);
        return count == null ? 0 : count;
    }

    private List<String> outboxPayloads(long auctionId, String eventType) {
        return jdbc.queryForList(
                "SELECT CAST(payload AS CHAR) FROM auction_outbox WHERE aggregate_id = ? AND event_type = ?",
                String.class, Long.toString(auctionId), eventType);
    }

    private record Fixture(long itemId, long auctionId) { }
}

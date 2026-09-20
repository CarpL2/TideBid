package io.github.carpl2.tidebid.auction.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionBidCommandIdempotencyTest {

    private static final Instant CREATED = Instant.parse("2026-09-20T06:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);
    private static final AuctionBidCommandIdempotency IDEMPOTENCY = new AuctionBidCommandIdempotency();

    @Test
    void missingCommandIsAnewRequest() {
        var decision = IDEMPOTENCY.inspect(null, request(AuctionBidCommandType.MANUAL_BID, HASH));

        assertThat(decision.type()).isEqualTo(AuctionBidCommandIdempotency.DecisionType.NEW);
        assertThat(decision.existing()).isNull();
    }

    @Test
    void sameSuccessfulRequestIsReturnedAsReplay() {
        AuctionBidCommand succeeded = succeededCommand();

        var decision = IDEMPOTENCY.inspect(succeeded,
                request(AuctionBidCommandType.MANUAL_BID, HASH));

        assertThat(decision.type()).isEqualTo(AuctionBidCommandIdempotency.DecisionType.REPLAY);
        assertThat(decision.existing()).isSameAs(succeeded);
    }

    @Test
    void sameProcessingRequestIsNotExecutedTwice() {
        AuctionBidCommand processing = IDEMPOTENCY.start(9, 1,
                request(AuctionBidCommandType.MANUAL_BID, HASH), CREATED);

        var decision = IDEMPOTENCY.inspect(processing,
                request(AuctionBidCommandType.MANUAL_BID, HASH));

        assertThat(decision.type()).isEqualTo(AuctionBidCommandIdempotency.DecisionType.IN_PROGRESS);
        assertThat(decision.existing()).isSameAs(processing);
    }

    @Test
    void sameRequestIdWithDifferentPayloadOrTypeIsRejected() {
        AuctionBidCommand succeeded = succeededCommand();

        assertThat(IDEMPOTENCY.inspect(succeeded,
                request(AuctionBidCommandType.MANUAL_BID, OTHER_HASH)).type())
                .isEqualTo(AuctionBidCommandIdempotency.DecisionType.PAYLOAD_CONFLICT);
        assertThat(IDEMPOTENCY.inspect(succeeded,
                request(AuctionBidCommandType.UPSERT_PROXY, HASH)).type())
                .isEqualTo(AuctionBidCommandIdempotency.DecisionType.PAYLOAD_CONFLICT);
    }

    @Test
    void completionStoresZeroToTwoBidSequencesAndWhetherActorLeads() {
        AuctionBidCommand processing = IDEMPOTENCY.start(9, 1,
                request(AuctionBidCommandType.MANUAL_BID, HASH), CREATED);
        AuctionBidCommandPlanner.Plan plan = new AuctionBidCommandPlanner.Plan(
                List.of(
                        new AuctionBidCommandPlanner.PlannedBid(101, BidSource.MANUAL,
                                new BigDecimal("200.00"), new BigDecimal("120.00"), 8),
                        new AuctionBidCommandPlanner.PlannedBid(201, BidSource.PROXY,
                                new BigDecimal("210.00"), new BigDecimal("200.00"), 9)
                ),
                201L, 11L, new BigDecimal("210.00"), new BigDecimal("220.00")
        );

        AuctionBidCommand completed = IDEMPOTENCY.complete(processing, plan, CREATED.plusSeconds(1));

        assertThat(completed.status()).isEqualTo(AuctionBidCommandStatus.SUCCEEDED);
        assertThat(completed.resultBidCount()).isEqualTo(2);
        assertThat(completed.firstSequenceNo()).isEqualTo(8L);
        assertThat(completed.lastSequenceNo()).isEqualTo(9L);
        assertThat(completed.resultPrice()).isEqualByComparingTo("210.00");
        assertThat(completed.resultLeading()).isFalse();
    }

    @Test
    void completionRejectsAlreadyCompletedCommands() {
        assertThatThrownBy(() -> IDEMPOTENCY.complete(
                succeededCommand(),
                new AuctionBidCommandPlanner.Plan(List.of(), 101L, null,
                        new BigDecimal("100.00"), new BigDecimal("110.00")),
                CREATED.plusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processing");
    }

    private static AuctionBidCommandIdempotency.Request request(
            AuctionBidCommandType type,
            String hash
    ) {
        return new AuctionBidCommandIdempotency.Request(101L, "request_0001", type, hash);
    }

    private static AuctionBidCommand succeededCommand() {
        return new AuctionBidCommand(
                9L, 1L, 101L, "request_0001", AuctionBidCommandType.MANUAL_BID, HASH,
                AuctionBidCommandStatus.SUCCEEDED, 1, new BigDecimal("200.00"), true,
                8L, 8L, CREATED, CREATED.plusSeconds(1)
        );
    }
}

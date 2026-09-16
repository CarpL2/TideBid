package io.github.carpl2.tidebid.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuctionMessageContractTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-15T12:00:00Z");

    @Test
    void bidAcceptedCarriesOnlyStableAcceptedBidFacts() {
        BidAcceptedEvent event = new BidAcceptedEvent(
                9_007_199_254_740_993L,
                9_007_199_254_740_994L,
                9_007_199_254_740_995L,
                new BigDecimal("2333.00"),
                7L,
                OCCURRED_AT
        );

        assertThat(BidAcceptedEvent.EVENT_TYPE).isEqualTo("auction.bid-accepted");
        assertThat(BidAcceptedEvent.SCHEMA_VERSION).isEqualTo(1);
        assertThat(event.amount()).isEqualByComparingTo("2333.00");
        assertThat(event.amount().scale()).isEqualTo(2);
        assertThat(event.sequenceNo()).isEqualTo(7L);
        assertThat(event.acceptedAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    void bidAcceptedRejectsInvalidIdentifiersMoneySequenceAndTime() {
        assertThatThrownBy(() -> bid(0, 2, 3, "1.00", 1, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auctionId");
        assertThatThrownBy(() -> bid(1, 0, 3, "1.00", 1, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bidId");
        assertThatThrownBy(() -> bid(1, 2, 0, "1.00", 1, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bidderId");
        assertThatThrownBy(() -> bid(1, 2, 3, "0.00", 1, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
        assertThatThrownBy(() -> bid(1, 2, 3, "1.001", 1, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decimal places");
        assertThatThrownBy(() -> bid(1, 2, 3, "100000000000000000.00", 1, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DECIMAL(19,2)");
        assertThatThrownBy(() -> bid(1, 2, 3, "1.00", 0, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequenceNo");
        assertThatThrownBy(() -> bid(1, 2, 3, "1.00", 1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("acceptedAt");
    }

    @Test
    void closeCommandCarriesExpectedDatabaseEndTime() {
        CloseAuctionCommand command = new CloseAuctionCommand(101L, OCCURRED_AT);

        assertThat(CloseAuctionCommand.EVENT_TYPE).isEqualTo("auction.close");
        assertThat(CloseAuctionCommand.SCHEMA_VERSION).isEqualTo(1);
        assertThat(command.auctionId()).isEqualTo(101L);
        assertThat(command.expectedEndAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    void closeCommandRejectsInvalidAuctionOrMissingExpectedEndTime() {
        assertThatThrownBy(() -> new CloseAuctionCommand(0, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auctionId");
        assertThatThrownBy(() -> new CloseAuctionCommand(1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("expectedEndAt");
    }

    private static BidAcceptedEvent bid(
            long auctionId,
            long bidId,
            long bidderId,
            String amount,
            long sequenceNo,
            Instant acceptedAt
    ) {
        return new BidAcceptedEvent(
                auctionId,
                bidId,
                bidderId,
                new BigDecimal(amount),
                sequenceNo,
                acceptedAt
        );
    }
}

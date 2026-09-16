package io.github.carpl2.tidebid.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AuctionClosedEventContractTest {

    private static final Instant ENDED_AT = Instant.parse("2026-09-16T02:00:00Z");
    private static final Instant CLOSED_AT = Instant.parse("2026-09-16T02:00:01Z");
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void soldEventCarriesImmutableOrderAndWinnerDepositSnapshot() throws Exception {
        AuctionClosedSoldEvent event = sold("REGISTRATION:9007199254740997", "1233.00", "2333.00");

        assertThat(AuctionClosedSoldEvent.EVENT_TYPE).isEqualTo("auction.closed-sold");
        assertThat(AuctionClosedSoldEvent.SCHEMA_VERSION).isEqualTo(1);
        assertThat(event.itemTitle()).isEqualTo("限量收藏品");
        assertThat(event.depositAmount()).isEqualByComparingTo("1233.00");
        assertThat(event.finalPrice()).isEqualByComparingTo("2333.00");
        assertThat(event.depositAmount().scale()).isEqualTo(2);
        assertThat(event.finalPrice().scale()).isEqualTo(2);

        String json = objectMapper.writeValueAsString(event);
        assertThat(json)
                .contains("\"auctionId\":\"9007199254740993\"")
                .contains("\"winnerId\":\"9007199254740996\"")
                .doesNotContain("nickname", "mobile", "phone", "token", "accessKey", "secret", "signedUrl");
    }

    @Test
    void unsoldEventHasNoWinnerBidPriceOrDepositFields() throws Exception {
        AuctionClosedUnsoldEvent event = new AuctionClosedUnsoldEvent(
                9_007_199_254_740_993L,
                9_007_199_254_740_994L,
                "限量收藏品",
                9_007_199_254_740_995L,
                ENDED_AT,
                CLOSED_AT
        );

        assertThat(AuctionClosedUnsoldEvent.EVENT_TYPE).isEqualTo("auction.closed-unsold");
        assertThat(AuctionClosedUnsoldEvent.SCHEMA_VERSION).isEqualTo(1);
        assertThat(Arrays.stream(AuctionClosedUnsoldEvent.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly("auctionId", "itemId", "itemTitle", "sellerId", "endedAt", "closedAt");
        assertThat(objectMapper.writeValueAsString(event))
                .doesNotContain("winner", "bid", "price", "deposit", "holdNo", "nickname", "mobile", "phone");
    }

    @Test
    void soldEventRejectsInvalidIdentitySnapshotMoneyAndTimeline() {
        assertThatThrownBy(() -> new AuctionClosedSoldEvent(
                1, 2, "限量收藏品", 3, 3, 5, "REGISTRATION:6",
                new BigDecimal("100.00"), new BigDecimal("200.00"), ENDED_AT, CLOSED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sellerId");
        assertThatThrownBy(() -> sold("unsafe hold no", "1233.00", "2333.00"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("winnerHoldNo");
        assertThatThrownBy(() -> sold("REGISTRATION:7", "0.00", "2333.00"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("depositAmount");
        assertThatThrownBy(() -> sold("REGISTRATION:7", "1233.00", "2333.001"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finalPrice");
        assertThatThrownBy(() -> new AuctionClosedSoldEvent(
                1, 2, "限量收藏品", 3, 4, 5, "REGISTRATION:6",
                new BigDecimal("100.00"), new BigDecimal("200.00"), CLOSED_AT, ENDED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("closedAt");
    }

    @Test
    void unsoldEventRejectsInvalidSnapshotAndTimeline() {
        assertThatThrownBy(() -> new AuctionClosedUnsoldEvent(
                0, 2, "限量收藏品", 3, ENDED_AT, CLOSED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("auctionId");
        assertThatThrownBy(() -> new AuctionClosedUnsoldEvent(
                1, 2, " ", 3, ENDED_AT, CLOSED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("itemTitle");
        assertThatThrownBy(() -> new AuctionClosedUnsoldEvent(
                1, 2, "限量收藏品", 3, CLOSED_AT, ENDED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("closedAt");
    }

    private static AuctionClosedSoldEvent sold(String holdNo, String depositAmount, String finalPrice) {
        return new AuctionClosedSoldEvent(
                9_007_199_254_740_993L,
                9_007_199_254_740_994L,
                " 限量收藏品 ",
                9_007_199_254_740_995L,
                9_007_199_254_740_996L,
                9_007_199_254_740_997L,
                holdNo,
                new BigDecimal(depositAmount),
                new BigDecimal(finalPrice),
                ENDED_AT,
                CLOSED_AT
        );
    }
}

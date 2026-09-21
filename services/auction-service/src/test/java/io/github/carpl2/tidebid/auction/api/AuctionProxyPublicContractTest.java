package io.github.carpl2.tidebid.auction.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandPlanner;
import io.github.carpl2.tidebid.auction.domain.BidSource;
import io.github.carpl2.tidebid.contracts.BidAcceptedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuctionProxyPublicContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void publicBidResponseAndEventDoNotContainProxyMaximum() throws Exception {
        String responseJson = MAPPER.writeValueAsString(new AuctionBidResponse.Accepted(
                "1", new BigDecimal("200.00"), false, true, new BigDecimal("210.00"),
                new BigDecimal("220.00"), 2, Instant.parse("2026-09-21T01:05:00Z"),
                false, false, 2, List.of(new AuctionBidResponse.PublicBid(
                        "88", new BigDecimal("210.00"), new BigDecimal("200.00"), 2,
                        BidSource.PROXY, false, Instant.parse("2026-09-21T01:00:00Z")))));
        String eventJson = MAPPER.writeValueAsString(new BidAcceptedEvent(
                1L, 88L, 201L, new BigDecimal("210.00"), 2,
                Instant.parse("2026-09-21T01:00:00Z")
        ));

        assertThat(responseJson).doesNotContain("maxAmount", "max_amount", "500.00");
        assertThat(eventJson).doesNotContain("maxAmount", "max_amount", "500.00");
    }

    @Test
    void plannerOutputContainsOnlyPublicBidAmounts() throws Exception {
        AuctionBidCommandPlanner.Plan plan = new AuctionBidCommandPlanner.Plan(
                List.of(new AuctionBidCommandPlanner.PlannedBid(
                        201L, BidSource.PROXY, new BigDecimal("210.00"),
                        new BigDecimal("200.00"), 2L
                )),
                201L, 11L, new BigDecimal("210.00"), new BigDecimal("220.00")
        );

        String json = MAPPER.writeValueAsString(plan);
        assertThat(json).doesNotContain("maxAmount", "max_amount", "500.00");
        assertThat(json).contains("210.00", "220.00");
    }
}

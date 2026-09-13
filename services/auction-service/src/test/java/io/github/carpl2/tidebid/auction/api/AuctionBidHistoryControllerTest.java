package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionBidQueryService;
import io.github.carpl2.tidebid.auction.infrastructure.security.AuctionSecurityConfiguration;
import io.github.carpl2.tidebid.security.JwtAccessTokenVerifier;
import io.github.carpl2.tidebid.security.JwtClaims;
import io.github.carpl2.tidebid.security.Role;
import io.github.carpl2.tidebid.web.CommonWebAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuctionBidHistoryController.class)
@ActiveProfiles("local-db")
@Import({AuctionSecurityConfiguration.class, CommonWebAutoConfiguration.class})
class AuctionBidHistoryControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-14T08:00:00Z");
    private static final long AUCTION_ID = 9_007_199_254_740_994L;
    private static final long BID_ID = 9_007_199_254_740_993L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuctionBidQueryService queryService;

    @MockitoBean
    private JwtAccessTokenVerifier tokenVerifier;

    @BeforeEach
    void authenticateToken() {
        when(tokenVerifier.verify("user-token")).thenReturn(new JwtClaims(
                "buyer", 42L, Set.of(Role.USER), NOW, NOW.plusSeconds(1800), "token-id"
        ));
    }

    @Test
    void returnsStablePageWithStringIdsAndNoBidderIdentity() throws Exception {
        when(queryService.find(42L, AUCTION_ID, 2, 10)).thenReturn(new AuctionBidQueryService.BidPage(
                AUCTION_ID, 2, 10, 11L, 2L,
                List.of(new AuctionBidQueryService.BidView(
                        BID_ID, new BigDecimal("130.00"), new BigDecimal("120.00"), 3L, NOW, false
                ))
        ));

        String response = mockMvc.perform(get("/api/auctions/{auctionId}/bids", Long.toString(AUCTION_ID))
                        .header("Authorization", "Bearer user-token")
                        .header("X-Trace-Id", "bid-history-trace")
                        .queryParam("page", "2")
                        .queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "bid-history-trace"))
                .andExpect(jsonPath("$.data.auctionId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[0].bidId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.items[0].amount").value(130.00))
                .andExpect(jsonPath("$.data.items[0].sequenceNo").value(3))
                .andExpect(jsonPath("$.data.items[0].mine").value(false))
                .andReturn().getResponse().getContentAsString();

        verify(queryService).find(42L, AUCTION_ID, 2, 10);
        assertThat(response).doesNotContain("bidderId", "requestId", "subject", "username");
    }

    @Test
    void rejectsAnonymousAndMalformedAuctionIdBeforeQuerying() throws Exception {
        mockMvc.perform(get("/api/auctions/88/bids"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/auctions/not-a-number/bids")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUCTION_INVALID"));

        verifyNoInteractions(queryService);
    }
}

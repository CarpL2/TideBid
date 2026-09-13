package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionBidConflictException;
import io.github.carpl2.tidebid.auction.application.AuctionBidService;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.auction.infrastructure.security.AuctionSecurityConfiguration;
import io.github.carpl2.tidebid.core.BusinessException;
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
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuctionBidController.class)
@ActiveProfiles("local-db")
@Import({AuctionSecurityConfiguration.class, CommonWebAutoConfiguration.class})
class AuctionBidControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-14T08:00:00Z");
    private static final long AUCTION_ID = 9_007_199_254_740_994L;
    private static final long BID_ID = 9_007_199_254_740_993L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuctionBidService bidService;

    @MockitoBean
    private JwtAccessTokenVerifier tokenVerifier;

    @BeforeEach
    void authenticateToken() {
        when(tokenVerifier.verify("user-token")).thenReturn(new JwtClaims(
                "buyer", 42L, Set.of(Role.USER), NOW, NOW.plusSeconds(1800), "token-id"
        ));
    }

    @Test
    void placesBidForAuthenticatedIdentityAndReturnsStringIds() throws Exception {
        when(bidService.place(any())).thenReturn(new BidRecord(
                BID_ID, AUCTION_ID, 42L, "bid-request-0001", new BigDecimal("110.00"),
                new BigDecimal("100.00"), 2, NOW
        ));

        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer user-token")
                        .header("X-Request-Id", "bid-request-0001")
                        .header("X-Trace-Id", "bid-trace-0001")
                        .contentType("application/json")
                        .content("""
                                {
                                  "auctionId": "9007199254740994",
                                  "amount": 110.00,
                                  "bidderId": "999"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "bid-trace-0001"))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.bidId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.auctionId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.amount").value(110.00))
                .andExpect(jsonPath("$.data.previousPrice").value(100.00))
                .andExpect(jsonPath("$.data.sequenceNo").value(2))
                .andExpect(jsonPath("$.data.createdAt").value("2026-09-14T08:00:00Z"));

        verify(bidService).place(new AuctionBidService.PlaceBidCommand(
                42L, AUCTION_ID, "bid-request-0001", new BigDecimal("110.00")
        ));
    }

    @Test
    void returnsStructuredLatestSnapshotForCasConflict() throws Exception {
        AuctionSession latest = new AuctionSession(
                AUCTION_ID, 81L, 7L, new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("50.00"), new BigDecimal("130.00"), 88L, 4,
                NOW.minusSeconds(300), NOW.plusSeconds(300), AuctionSessionStatus.OPEN, 9,
                NOW.minusSeconds(600), NOW
        );
        when(bidService.place(any())).thenThrow(new AuctionBidConflictException(latest));

        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer user-token")
                        .header("X-Request-Id", "bid-request-0002")
                        .header("X-Trace-Id", "bid-trace-0002")
                        .contentType("application/json")
                        .content("{\"auctionId\":\"9007199254740994\",\"amount\":\"120.00\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_BID_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Auction price changed before the bid was accepted"))
                .andExpect(jsonPath("$.traceId").value("bid-trace-0002"))
                .andExpect(jsonPath("$.data.auctionId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.currentPrice").value(130.00))
                .andExpect(jsonPath("$.data.minimumNextBid").value(140.00))
                .andExpect(jsonPath("$.data.bidCount").value(4))
                .andExpect(jsonPath("$.data.version").value(9))
                .andExpect(jsonPath("$.data.status").value("OPEN"));
    }

    @Test
    void mapsOrdinaryBusinessConflictThroughSharedHandler() throws Exception {
        when(bidService.place(any())).thenThrow(new BusinessException(AuctionErrorCode.BID_TOO_LOW));

        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer user-token")
                        .header("X-Request-Id", "bid-request-0003")
                        .contentType("application/json")
                        .content("{\"auctionId\":\"88\",\"amount\":\"100.00\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_BID_TOO_LOW"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void mapsIdempotencyPayloadConflictToStableHttpConflict() throws Exception {
        when(bidService.place(any())).thenThrow(new BusinessException(AuctionErrorCode.IDEMPOTENCY_CONFLICT));

        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer user-token")
                        .header("X-Request-Id", "reused-request-01")
                        .header("X-Trace-Id", "bid-trace-0003")
                        .contentType("application/json")
                        .content("{\"auctionId\":\"88\",\"amount\":\"120.00\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.traceId").value("bid-trace-0003"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void rejectsUnauthenticatedAndMalformedRequestsBeforeApplication() throws Exception {
        mockMvc.perform(post("/api/bids")
                        .header("X-Request-Id", "bid-request-0004")
                        .contentType("application/json")
                        .content("{\"auctionId\":\"88\",\"amount\":\"100.00\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer user-token")
                        .header("X-Request-Id", "bid-request-0005")
                        .contentType("application/json")
                        .content("{\"auctionId\":\"not-a-number\",\"amount\":\"100.00\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer user-token")
                        .header("X-Request-Id", "bid-request-0006")
                        .contentType("application/json")
                        .content("{\"auctionId\":\"88\",\"amount\":null}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer user-token")
                        .header("X-Request-Id", "short")
                        .contentType("application/json")
                        .content("{\"auctionId\":\"88\",\"amount\":\"100.00\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer user-token")
                        .contentType("application/json")
                        .content("{\"auctionId\":\"88\",\"amount\":\"100.00\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bidService);
    }
}

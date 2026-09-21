package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionProxyBidApplicationService;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommand;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandType;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBid;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBidStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuctionProxyBidController.class)
@ActiveProfiles("local-db")
@Import({AuctionSecurityConfiguration.class, CommonWebAutoConfiguration.class})
class AuctionProxyBidControllerTest {

    private static final long AUCTION_ID = 9_007_199_254_740_994L;
    private static final long PROXY_ID = 9_007_199_254_740_993L;
    private static final Instant NOW = Instant.parse("2026-09-21T03:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AuctionProxyBidApplicationService service;
    @MockitoBean private JwtAccessTokenVerifier tokenVerifier;

    @BeforeEach
    void authenticate() {
        when(tokenVerifier.verify("user-token")).thenReturn(new JwtClaims(
                "buyer", 42L, Set.of(Role.USER), NOW, NOW.plusSeconds(1800), "token-id"));
    }

    @Test
    void returnsOnlyTheAuthenticatedUsersProxyWithStringIds() throws Exception {
        when(service.findMine(42L, AUCTION_ID)).thenReturn(java.util.Optional.of(proxy()));

        String body = mockMvc.perform(get("/api/auctions/{auctionId}/proxy-bid", Long.toString(AUCTION_ID))
                        .header("Authorization", "Bearer user-token")
                        .header("X-Trace-Id", "proxy-trace"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "proxy-trace"))
                .andExpect(jsonPath("$.data.auctionId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.proxyBidId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.maxAmount").value(500.00))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("bidderId");
    }

    @Test
    void writesRequireRequestIdAndUseJwtIdentity() throws Exception {
        when(service.upsert(42L, AUCTION_ID, "proxy-req-01", new BigDecimal("500.00")))
                .thenReturn(result());

        mockMvc.perform(put("/api/auctions/{auctionId}/proxy-bid", Long.toString(AUCTION_ID))
                        .header("Authorization", "Bearer user-token")
                        .header("X-Request-Id", "proxy-req-01")
                        .contentType("application/json")
                        .content("{\"maxAmount\":\"500.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auctionId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.leading").value(true))
                .andExpect(jsonPath("$.data.extended").value(false));

        verify(service).upsert(42L, AUCTION_ID, "proxy-req-01", new BigDecimal("500.00"));
    }

    @Test
    void rejectsMalformedIdAndMissingRequestIdBeforeApplication() throws Exception {
        mockMvc.perform(get("/api/auctions/not-a-number/proxy-bid")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/auctions/{auctionId}/proxy-bid", Long.toString(AUCTION_ID))
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isBadRequest());
    }

    private static AuctionProxyBid proxy() {
        return new AuctionProxyBid(PROXY_ID, AUCTION_ID, 42L, new BigDecimal("500.00"),
                AuctionProxyBidStatus.ACTIVE, 1L, 0L, null, NOW, NOW);
    }

    private static AuctionProxyBidApplicationService.Result result() {
        AuctionBidCommand command = new AuctionBidCommand(
                81L, AUCTION_ID, 42L, "proxy-req-01", AuctionBidCommandType.UPSERT_PROXY,
                "a".repeat(64), AuctionBidCommandStatus.SUCCEEDED, 0,
                new BigDecimal("120.00"), true, null, null, NOW, NOW);
        AuctionSession session = new AuctionSession(
                AUCTION_ID, 601L, 7L, new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("50.00"), new BigDecimal("120.00"), 42L, 2L,
                NOW.minusSeconds(300), NOW.plusSeconds(600), AuctionSessionStatus.OPEN,
                3L, NOW.minusSeconds(10), NOW.minusSeconds(10));
        return new AuctionProxyBidApplicationService.Result(command, List.of(), session, proxy(), false);
    }
}

package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionAssetQueryService;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
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
import java.net.URI;
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

@WebMvcTest(AuctionLobbyController.class)
@ActiveProfiles("local-db")
@Import({AuctionSecurityConfiguration.class, CommonWebAutoConfiguration.class})
class AuctionLobbyControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-13T04:00:00Z");
    private static final long PUBLIC_ID = 9_007_199_254_740_993L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuctionAssetQueryService queryService;

    @MockitoBean
    private JwtAccessTokenVerifier tokenVerifier;

    @BeforeEach
    void authenticateToken() {
        when(tokenVerifier.verify("user-token")).thenReturn(new JwtClaims(
                "buyer", 88L, Set.of(Role.USER), NOW, NOW.plusSeconds(1800), "token-id"
        ));
    }

    @Test
    void returnsAuthenticatedLobbyWithStringIdsAndNoStorageInternals() throws Exception {
        when(queryService.findLobby(2, 10)).thenReturn(new AuctionAssetQueryService.PageResult<>(
                2, 10, 11L, List.of(summary())
        ));

        String response = mockMvc.perform(get("/api/auctions")
                        .header("Authorization", "Bearer user-token")
                        .header("X-Trace-Id", "auction-lobby-trace")
                        .queryParam("page", "2")
                        .queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "auction-lobby-trace"))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[0].itemId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.items[0].auctionId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.items[0].displayPrice").value(130.00))
                .andExpect(jsonPath("$.data.items[0].minimumNextBid").value(140.00))
                .andExpect(jsonPath("$.data.items[0].coverImage.imageId").value("9007199254740995"))
                .andReturn().getResponse().getContentAsString();

        verify(queryService).findLobby(2, 10);
        assertThat(response).doesNotContain("objectKey", "originalFilename", "sellerId");
    }

    @Test
    void rejectsAnonymousLobbyBeforeQuerying() throws Exception {
        mockMvc.perform(get("/api/auctions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED"));

        verifyNoInteractions(queryService);
    }

    private static AuctionAssetQueryService.LobbySummary summary() {
        return new AuctionAssetQueryService.LobbySummary(
                PUBLIC_ID, PUBLIC_ID + 1, "Mechanical keyboard", "ELECTRONICS",
                AuctionItemCondition.GOOD, AuctionSessionStatus.OPEN,
                new BigDecimal("100.00"), new BigDecimal("130.00"), new BigDecimal("130.00"),
                new BigDecimal("140.00"), 3L, NOW.minusSeconds(60), NOW.plusSeconds(3600),
                new AuctionAssetQueryService.LobbyCover(
                        PUBLIC_ID + 2, "image/webp",
                        URI.create("https://object-storage.invalid/read?signature=secret"), NOW.plusSeconds(300)
                )
        );
    }
}

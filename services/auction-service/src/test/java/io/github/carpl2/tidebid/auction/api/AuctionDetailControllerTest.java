package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionDetailQueryService;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
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

@WebMvcTest(AuctionDetailController.class)
@ActiveProfiles("local-db")
@Import({AuctionSecurityConfiguration.class, CommonWebAutoConfiguration.class})
class AuctionDetailControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-13T05:00:00Z");
    private static final long PUBLIC_ID = 9_007_199_254_740_993L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuctionDetailQueryService queryService;

    @MockitoBean
    private JwtAccessTokenVerifier tokenVerifier;

    @BeforeEach
    void authenticateToken() {
        when(tokenVerifier.verify("user-token")).thenReturn(new JwtClaims(
                "buyer", 88L, Set.of(Role.USER), NOW, NOW.plusSeconds(1800), "token-id"
        ));
    }

    @Test
    void returnsStringIdsCurrentUsersRegistrationAndNoStorageOrSellerInternals() throws Exception {
        when(queryService.find(88L, PUBLIC_ID + 1)).thenReturn(detail());

        String response = mockMvc.perform(get("/api/auctions/{auctionId}", Long.toString(PUBLIC_ID + 1))
                        .header("Authorization", "Bearer user-token")
                        .header("X-Trace-Id", "auction-detail-trace"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "auction-detail-trace"))
                .andExpect(jsonPath("$.data.itemId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.auctionId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.minimumNextBid").value(140.00))
                .andExpect(jsonPath("$.data.images[0].imageId").value("9007199254740995"))
                .andExpect(jsonPath("$.data.myRegistration.registrationId").value("9007199254740996"))
                .andExpect(jsonPath("$.data.myRegistration.status").value("REGISTERED"))
                .andReturn().getResponse().getContentAsString();

        verify(queryService).find(88L, PUBLIC_ID + 1);
        assertThat(response).doesNotContain(
                "sellerId", "objectKey", "originalFilename", "registrationNo", "leaseOwner"
        );
    }

    @Test
    void rejectsAnonymousAndMalformedIdsBeforeQuerying() throws Exception {
        mockMvc.perform(get("/api/auctions/101"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED"));
        mockMvc.perform(get("/api/auctions/not-a-number")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUCTION_INVALID"));

        verifyNoInteractions(queryService);
    }

    @Test
    void mapsMissingVisibleAuctionToNotFound() throws Exception {
        when(queryService.find(88L, 101L))
                .thenThrow(new BusinessException(AuctionErrorCode.AUCTION_NOT_FOUND));

        mockMvc.perform(get("/api/auctions/101")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));

        verify(queryService).find(88L, 101L);
    }

    private static AuctionDetailQueryService.AuctionDetail detail() {
        return new AuctionDetailQueryService.AuctionDetail(
                PUBLIC_ID, PUBLIC_ID + 1, "Mechanical keyboard",
                "A sufficiently detailed item description", "ELECTRONICS", AuctionItemCondition.GOOD,
                AuctionSessionStatus.OPEN, new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("50.00"), new BigDecimal("130.00"), new BigDecimal("130.00"),
                new BigDecimal("140.00"), 3L, NOW.minusSeconds(60), NOW.plusSeconds(3600), false,
                List.of(new AuctionDetailQueryService.ImageView(
                        PUBLIC_ID + 2, "image/webp", 4096L, 0,
                        URI.create("https://object-storage.invalid/read?signature=secret"), NOW.plusSeconds(300)
                )),
                new AuctionDetailQueryService.RegistrationView(
                        PUBLIC_ID + 3, AuctionRegistrationStatus.REGISTERED, null, NOW.minusSeconds(120)
                )
        );
    }
}

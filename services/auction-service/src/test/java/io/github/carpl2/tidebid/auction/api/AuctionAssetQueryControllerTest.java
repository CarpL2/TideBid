package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionAssetQueryService;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionReviewDecision;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuctionAssetQueryController.class)
@ActiveProfiles("local-db")
@Import({AuctionSecurityConfiguration.class, CommonWebAutoConfiguration.class})
class AuctionAssetQueryControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private static final long PUBLIC_ITEM_ID = 9_007_199_254_740_993L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuctionAssetQueryService queryService;

    @MockitoBean
    private JwtAccessTokenVerifier tokenVerifier;

    @BeforeEach
    void authenticateTokens() {
        when(tokenVerifier.verify("user-token")).thenReturn(new JwtClaims(
                "seller", 42L, Set.of(Role.USER), NOW, NOW.plusSeconds(1800), "user-token-id"
        ));
        when(tokenVerifier.verify("admin-token")).thenReturn(new JwtClaims(
                "admin", 99L, Set.of(Role.USER, Role.ADMIN), NOW, NOW.plusSeconds(1800), "admin-token-id"
        ));
    }

    @Test
    void returnsSellerPageWithStringIdsAndTrustedIdentity() throws Exception {
        when(queryService.findMine(42L, 2, 10)).thenReturn(new AuctionAssetQueryService.PageResult<>(
                2, 10, 11L, List.of(summary())
        ));

        mockMvc.perform(get("/api/assets/mine")
                        .header("Authorization", "Bearer user-token")
                        .header("X-Trace-Id", "asset-query-trace")
                        .queryParam("page", "2")
                        .queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "asset-query-trace"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[0].itemId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.items[0].auctionId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.items[0].coverImage.imageId").value("9007199254740995"))
                .andExpect(jsonPath("$.data.items[0].coverImage.previewUrl")
                        .value("https://object-storage.invalid/read?signature=secret"));

        verify(queryService).findMine(42L, 2, 10);
    }

    @Test
    void passesAdministratorRoleToDetailPolicyAndOmitsReviewerIdentity() throws Exception {
        when(queryService.findDetail(99L, true, PUBLIC_ITEM_ID)).thenReturn(detail());

        String response = mockMvc.perform(get("/api/assets/{assetId}", Long.toString(PUBLIC_ITEM_ID))
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.itemId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.sellerId").value("42"))
                .andExpect(jsonPath("$.data.latestReview.decision").value("REJECTED"))
                .andExpect(jsonPath("$.data.latestReview.comment").value("Clearer images required"))
                .andReturn().getResponse().getContentAsString();

        verify(queryService).findDetail(99L, true, PUBLIC_ITEM_ID);
        assertThat(response).doesNotContain("reviewerId");
    }

    @Test
    void rejectsMissingAuthenticationBeforeQuerying() throws Exception {
        mockMvc.perform(get("/api/assets/mine"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED"));

        verifyNoInteractions(queryService);
    }

    @Test
    void rejectsMalformedOrOverflowingAssetId() throws Exception {
        mockMvc.perform(get("/api/assets/not-a-number")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUCTION_ASSET_INVALID"));

        mockMvc.perform(get("/api/assets/999999999999999999999999")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUCTION_ASSET_INVALID"));

        verify(queryService, org.mockito.Mockito.never()).findDetail(anyLong(), eq(false), anyLong());
    }

    @Test
    void mapsObjectLevelAccessDenialToForbidden() throws Exception {
        when(queryService.findDetail(42L, false, 101L))
                .thenThrow(new BusinessException(AuctionErrorCode.ASSET_ACCESS_DENIED));

        mockMvc.perform(get("/api/assets/101")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUCTION_ASSET_ACCESS_DENIED"));
    }

    private static AuctionAssetQueryService.AssetSummary summary() {
        return new AuctionAssetQueryService.AssetSummary(
                PUBLIC_ITEM_ID, PUBLIC_ITEM_ID + 1,
                "Mechanical keyboard", "ELECTRONICS", AuctionItemCondition.GOOD,
                AuctionItemReviewStatus.DRAFT, AuctionSessionStatus.DRAFT,
                new BigDecimal("100.00"), null, NOW.plusSeconds(3600), NOW.plusSeconds(7200),
                2L, 3L,
                new AuctionAssetQueryService.ImageView(
                        PUBLIC_ITEM_ID + 2, "dev/users/42/202609/image.webp", "image.webp",
                        "image/webp", 4096L, 0,
                        URI.create("https://object-storage.invalid/read?signature=secret"), NOW.plusSeconds(300)
                ),
                NOW.minusSeconds(3600), NOW.minusSeconds(300)
        );
    }

    private static AuctionAssetQueryService.AssetDetail detail() {
        return new AuctionAssetQueryService.AssetDetail(
                PUBLIC_ITEM_ID, PUBLIC_ITEM_ID + 1, 42L,
                "Mechanical keyboard", "A sufficiently detailed item description", "ELECTRONICS",
                AuctionItemCondition.GOOD, AuctionItemReviewStatus.REJECTED, 1,
                AuctionSessionStatus.DRAFT,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, 0L, NOW.plusSeconds(3600), NOW.plusSeconds(7200), 2L, 3L,
                NOW.minusSeconds(1200), null, NOW.minusSeconds(3600), NOW.minusSeconds(300),
                List.of(),
                new AuctionAssetQueryService.ReviewFeedback(
                        1, AuctionReviewDecision.REJECTED, "Clearer images required", NOW.minusSeconds(600)
                )
        );
    }
}

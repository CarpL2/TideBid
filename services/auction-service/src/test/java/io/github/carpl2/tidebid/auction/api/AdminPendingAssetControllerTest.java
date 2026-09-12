package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionAssetQueryService;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
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

@WebMvcTest(AdminPendingAssetController.class)
@ActiveProfiles("local-db")
@Import({AuctionSecurityConfiguration.class, CommonWebAutoConfiguration.class})
class AdminPendingAssetControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private static final long ITEM_ID = 9_007_199_254_740_993L;

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
    void returnsPendingPageWithStringIdsAndWithoutStorageCredentials() throws Exception {
        when(queryService.findPendingReviews(true, 2, 10)).thenReturn(
                new AuctionAssetQueryService.PageResult<>(2, 10, 11L, List.of(summary()))
        );

        String response = mockMvc.perform(get("/api/admin/assets/pending")
                        .header("Authorization", "Bearer admin-token")
                        .header("X-Trace-Id", "admin-pending-trace")
                        .queryParam("page", "2")
                        .queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "admin-pending-trace"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[0].itemId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.items[0].auctionId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.items[0].sellerId").value("42"))
                .andExpect(jsonPath("$.data.items[0].submissionVersion").value(1))
                .andExpect(jsonPath("$.data.items[0].coverImage.imageId").value("9007199254740995"))
                .andExpect(jsonPath("$.data.items[0].coverImage.previewUrl")
                        .value("https://object-storage.invalid/read?signature=secret"))
                .andReturn().getResponse().getContentAsString();

        verify(queryService).findPendingReviews(true, 2, 10);
        assertThat(response).doesNotContain(
                "objectKey", "originalFilename", "checksum", "reviewerId", "accessKey", "accessSecret"
        );
    }

    @Test
    void rejectsOrdinaryUserBeforeQueryingReviewQueue() throws Exception {
        mockMvc.perform(get("/api/admin/assets/pending")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(queryService);
    }

    @Test
    void rejectsMissingAuthenticationBeforeQueryingReviewQueue() throws Exception {
        mockMvc.perform(get("/api/admin/assets/pending"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED"));

        verifyNoInteractions(queryService);
    }

    private static AuctionAssetQueryService.AdminReviewSummary summary() {
        return new AuctionAssetQueryService.AdminReviewSummary(
                ITEM_ID, ITEM_ID + 1, 42L,
                "Mechanical keyboard", "ELECTRONICS", AuctionItemCondition.GOOD,
                AuctionItemReviewStatus.PENDING_REVIEW, 1,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), 2L, 3L, NOW.minusSeconds(600),
                new AuctionAssetQueryService.ImageView(
                        ITEM_ID + 2, "dev/users/42/202609/image.webp", "private-name.webp",
                        "image/webp", 4096L, 0,
                        URI.create("https://object-storage.invalid/read?signature=secret"), NOW.plusSeconds(300)
                )
        );
    }
}

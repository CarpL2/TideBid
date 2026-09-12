package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionAssetQueryService;
import io.github.carpl2.tidebid.auction.application.AuctionReviewService;
import io.github.carpl2.tidebid.auction.application.port.AuctionReviewTransaction;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionReview;
import io.github.carpl2.tidebid.auction.domain.AuctionReviewDecision;
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
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private AuctionReviewService reviewService;

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

    @Test
    void approvesCurrentSubmissionWithTrustedAdministratorIdentity() throws Exception {
        when(reviewService.review(new AuctionReviewService.ReviewCommand(
                99L, ITEM_ID, 1, AuctionReviewDecision.APPROVED, "Looks good"
        )))
                .thenReturn(approvedAuction());

        String response = mockMvc.perform(post("/api/admin/assets/{assetId}/reviews", Long.toString(ITEM_ID))
                        .header("Authorization", "Bearer admin-token")
                        .header("X-Request-Id", "approve-request-01")
                        .header("X-Trace-Id", "approve-trace-01")
                        .contentType("application/json")
                        .content("""
                                {"decision":"APPROVE","submissionVersion":1,"comment":"Looks good"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "approve-trace-01"))
                .andExpect(jsonPath("$.data.itemId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.auctionId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.decision").value("APPROVED"))
                .andExpect(jsonPath("$.data.itemStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.sessionStatus").value("SCHEDULED"))
                .andReturn().getResponse().getContentAsString();

        verify(reviewService).review(new AuctionReviewService.ReviewCommand(
                99L, ITEM_ID, 1, AuctionReviewDecision.APPROVED, "Looks good"
        ));
        assertThat(response).doesNotContain("reviewerId", "sellerId", "comment");
    }

    @Test
    void rejectsCurrentSubmissionWithTrustedAdministratorIdentity() throws Exception {
        when(reviewService.review(new AuctionReviewService.ReviewCommand(
                99L, ITEM_ID, 1, AuctionReviewDecision.REJECTED, "Add clearer photos"
        ))).thenReturn(rejectedAuction());

        String response = mockMvc.perform(post("/api/admin/assets/{assetId}/reviews", Long.toString(ITEM_ID))
                        .header("Authorization", "Bearer admin-token")
                        .header("X-Request-Id", "reject-request-01")
                        .contentType("application/json")
                        .content("""
                                {"decision":"REJECT","submissionVersion":1,"comment":"Add clearer photos"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("REJECTED"))
                .andExpect(jsonPath("$.data.itemStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.sessionStatus").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();

        verify(reviewService).review(new AuctionReviewService.ReviewCommand(
                99L, ITEM_ID, 1, AuctionReviewDecision.REJECTED, "Add clearer photos"
        ));
        assertThat(response).doesNotContain("reviewerId", "sellerId", "comment");
    }

    @Test
    void rejectsOrdinaryUserAndMissingRequestIdBeforeApproval() throws Exception {
        String body = """
                {"decision":"APPROVE","submissionVersion":1}
                """;
        mockMvc.perform(post("/api/admin/assets/101/reviews")
                        .header("Authorization", "Bearer user-token")
                        .header("X-Request-Id", "approve-request-02")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/assets/101/reviews")
                        .header("Authorization", "Bearer admin-token")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reviewService);
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

    private static AuctionReviewTransaction.ReviewedAuction approvedAuction() {
        AuctionItem item = new AuctionItem(
                ITEM_ID, 42L, "Mechanical keyboard", "A submitted auction item for review",
                "ELECTRONICS", AuctionItemCondition.GOOD, AuctionItemReviewStatus.APPROVED,
                1, 3L, NOW.minusSeconds(600), NOW, NOW.minusSeconds(3600), NOW
        );
        AuctionSession session = new AuctionSession(
                ITEM_ID + 1, ITEM_ID, 42L,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0L, NOW.plusSeconds(3600), NOW.plusSeconds(7200),
                AuctionSessionStatus.SCHEDULED, 4L, NOW.minusSeconds(3600), NOW
        );
        AuctionReview review = new AuctionReview(
                ITEM_ID + 3, ITEM_ID, 1, 99L, AuctionReviewDecision.APPROVED, "Looks good", NOW
        );
        return new AuctionReviewTransaction.ReviewedAuction(item, session, review);
    }

    private static AuctionReviewTransaction.ReviewedAuction rejectedAuction() {
        AuctionItem item = new AuctionItem(
                ITEM_ID, 42L, "Mechanical keyboard", "A submitted auction item for review",
                "ELECTRONICS", AuctionItemCondition.GOOD, AuctionItemReviewStatus.REJECTED,
                1, 3L, NOW.minusSeconds(600), null, NOW.minusSeconds(3600), NOW
        );
        AuctionSession session = new AuctionSession(
                ITEM_ID + 1, ITEM_ID, 42L,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0L, NOW.plusSeconds(3600), NOW.plusSeconds(7200),
                AuctionSessionStatus.DRAFT, 3L, NOW.minusSeconds(3600), NOW.minusSeconds(600)
        );
        AuctionReview review = new AuctionReview(
                ITEM_ID + 3, ITEM_ID, 1, 99L, AuctionReviewDecision.REJECTED, "Add clearer photos", NOW
        );
        return new AuctionReviewTransaction.ReviewedAuction(item, session, review);
    }
}

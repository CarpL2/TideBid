package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionSubmissionService;
import io.github.carpl2.tidebid.auction.application.port.AuctionSubmissionTransaction;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
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
import org.springframework.http.MediaType;
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

@WebMvcTest(AuctionSubmissionController.class)
@ActiveProfiles("local-db")
@Import({AuctionSecurityConfiguration.class, CommonWebAutoConfiguration.class})
class AuctionSubmissionControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private static final long ITEM_ID = 9_007_199_254_740_993L;

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AuctionSubmissionService submissionService;
    @MockitoBean private JwtAccessTokenVerifier tokenVerifier;

    @BeforeEach
    void authenticateUser() {
        when(tokenVerifier.verify("seller-token")).thenReturn(new JwtClaims(
                "seller", 42L, Set.of(Role.USER), NOW, NOW.plusSeconds(1800), "token-12345678"
        ));
    }

    @Test
    void submitsAuthenticatedSellersCurrentVersionAndReturnsStringIds() throws Exception {
        when(submissionService.submit(any())).thenReturn(submitted());

        mockMvc.perform(post("/api/assets/{assetId}/submit", Long.toString(ITEM_ID))
                        .header("Authorization", "Bearer seller-token")
                        .header("X-Request-Id", "submit-request-001")
                        .header("X-Trace-Id", "submit-trace-1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemVersion":3,"sessionVersion":5,"sellerId":"999"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "submit-trace-1234"))
                .andExpect(jsonPath("$.data.itemId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.auctionId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.submissionVersion").value(1))
                .andExpect(jsonPath("$.data.itemVersion").value(4));

        var captor = org.mockito.ArgumentCaptor.forClass(AuctionSubmissionService.SubmitCommand.class);
        verify(submissionService).submit(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(
                new AuctionSubmissionService.SubmitCommand(42L, ITEM_ID, 3L, 5L)
        );
    }

    @Test
    void rejectsUnauthenticatedMissingRequestIdAndInvalidBody() throws Exception {
        String validBody = "{\"itemVersion\":0,\"sessionVersion\":0}";
        mockMvc.perform(post("/api/assets/101/submit")
                        .header("X-Request-Id", "submit-request-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/assets/101/submit")
                        .header("Authorization", "Bearer seller-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        mockMvc.perform(post("/api/assets/101/submit")
                        .header("Authorization", "Bearer seller-token")
                        .header("X-Request-Id", "submit-request-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemVersion\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verifyNoInteractions(submissionService);
    }

    @Test
    void rejectsMalformedAssetIdBeforeSubmission() throws Exception {
        mockMvc.perform(post("/api/assets/not-a-number/submit")
                        .header("Authorization", "Bearer seller-token")
                        .header("X-Request-Id", "submit-request-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemVersion\":0,\"sessionVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUCTION_ASSET_INVALID"));

        verifyNoInteractions(submissionService);
    }

    @Test
    void mapsStateConflictAndStorageFailureToStableResponses() throws Exception {
        when(submissionService.submit(any()))
                .thenThrow(new BusinessException(AuctionErrorCode.ASSET_STATE_CONFLICT))
                .thenThrow(new BusinessException(AuctionErrorCode.STORAGE_UNAVAILABLE));
        String body = "{\"itemVersion\":0,\"sessionVersion\":0}";

        mockMvc.perform(post("/api/assets/101/submit")
                        .header("Authorization", "Bearer seller-token")
                        .header("X-Request-Id", "submit-request-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_ASSET_STATE_CONFLICT"));

        mockMvc.perform(post("/api/assets/101/submit")
                        .header("Authorization", "Bearer seller-token")
                        .header("X-Request-Id", "submit-request-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUCTION_STORAGE_UNAVAILABLE"));
    }

    private static AuctionSubmissionTransaction.SubmittedAuction submitted() {
        AuctionItem item = new AuctionItem(
                ITEM_ID, 42L, "Mechanical keyboard", "A complete auction item description",
                "ELECTRONICS", AuctionItemCondition.GOOD, AuctionItemReviewStatus.PENDING_REVIEW,
                1, 4L, NOW, null, NOW.minusSeconds(3600), NOW
        );
        AuctionSession session = new AuctionSession(
                ITEM_ID + 1, ITEM_ID, 42L,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0L, NOW.plusSeconds(3600), NOW.plusSeconds(7200),
                AuctionSessionStatus.DRAFT, 5L, NOW.minusSeconds(3600), NOW.minusSeconds(300)
        );
        return new AuctionSubmissionTransaction.SubmittedAuction(item, session);
    }
}

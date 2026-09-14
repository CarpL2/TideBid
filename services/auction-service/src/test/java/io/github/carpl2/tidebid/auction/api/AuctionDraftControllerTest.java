package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionDraftCreationService;
import io.github.carpl2.tidebid.auction.application.AuctionDraftUpdateService;
import io.github.carpl2.tidebid.auction.application.port.AuctionDraftTransaction;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.infrastructure.security.AuctionSecurityConfiguration;
import io.github.carpl2.tidebid.security.JwtAccessTokenVerifier;
import io.github.carpl2.tidebid.security.JwtClaims;
import io.github.carpl2.tidebid.security.Role;
import io.github.carpl2.tidebid.web.CommonWebAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuctionDraftController.class)
@ActiveProfiles("local-db")
@Import({AuctionSecurityConfiguration.class, CommonWebAutoConfiguration.class})
class AuctionDraftControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-14T08:00:00Z");
    private static final long ITEM_ID = 9_007_199_254_740_993L;

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AuctionDraftCreationService creationService;
    @MockitoBean private AuctionDraftUpdateService updateService;
    @MockitoBean private JwtAccessTokenVerifier tokenVerifier;

    @BeforeEach
    void authenticateSeller() {
        when(tokenVerifier.verify("seller-token")).thenReturn(new JwtClaims(
                "seller", 42L, Set.of(Role.USER), NOW, NOW.plusSeconds(1800), "seller-token-id"
        ));
    }

    @Test
    void createsDraftFromAuthenticatedSellerAndReturnsStringIds() throws Exception {
        when(creationService.create(any())).thenReturn(createdDraft());

        mockMvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer seller-token")
                        .header("X-Request-Id", "create-draft-001")
                        .header("X-Trace-Id", "create-draft-trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Trace-Id", "create-draft-trace"))
                .andExpect(jsonPath("$.data.itemId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.auctionId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.reviewStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.sessionStatus").value("DRAFT"));

        ArgumentCaptor<AuctionDraftCreationService.CreateDraftCommand> captor =
                ArgumentCaptor.forClass(AuctionDraftCreationService.CreateDraftCommand.class);
        verify(creationService).create(captor.capture());
        assertThat(captor.getValue().sellerId()).isEqualTo(42L);
        assertThat(captor.getValue().imageObjectKeys()).containsExactly("dev/users/42/202609/image.webp");
    }

    @Test
    void updatesOwnedDraftUsingBothExpectedVersions() throws Exception {
        when(updateService.update(any())).thenReturn(updatedDraft());

        mockMvc.perform(put("/api/assets/{assetId}", Long.toString(ITEM_ID))
                        .header("Authorization", "Bearer seller-token")
                        .header("X-Request-Id", "update-draft-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.itemVersion").value(4))
                .andExpect(jsonPath("$.data.sessionVersion").value(6));

        ArgumentCaptor<AuctionDraftUpdateService.UpdateDraftCommand> captor =
                ArgumentCaptor.forClass(AuctionDraftUpdateService.UpdateDraftCommand.class);
        verify(updateService).update(captor.capture());
        assertThat(captor.getValue().sellerId()).isEqualTo(42L);
        assertThat(captor.getValue().itemId()).isEqualTo(ITEM_ID);
        assertThat(captor.getValue().expectedItemVersion()).isEqualTo(3L);
        assertThat(captor.getValue().expectedSessionVersion()).isEqualTo(5L);
    }

    @Test
    void rejectsMissingAuthenticationRequestIdAndInvalidBody() throws Exception {
        mockMvc.perform(post("/api/assets")
                        .header("X-Request-Id", "create-draft-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer seller-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer seller-token")
                        .header("X-Request-Id", "create-draft-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verifyNoInteractions(creationService, updateService);
    }

    @Test
    void rejectsMalformedAssetIdBeforeUpdating() throws Exception {
        mockMvc.perform(put("/api/assets/not-a-number")
                        .header("Authorization", "Bearer seller-token")
                        .header("X-Request-Id", "update-draft-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUCTION_ASSET_INVALID"));

        verifyNoInteractions(updateService);
    }

    private static String createBody() {
        return """
                {
                  "title":"Mechanical keyboard",
                  "description":"A complete auction item description",
                  "category":"ELECTRONICS",
                  "itemCondition":"GOOD",
                  "startPrice":100.00,
                  "bidIncrement":10.00,
                  "depositAmount":50.00,
                  "startAt":"2026-09-15T08:00:00Z",
                  "endAt":"2026-09-15T10:00:00Z",
                  "imageObjectKeys":["dev/users/42/202609/image.webp"],
                  "sellerId":"999"
                }
                """;
    }

    private static String updateBody() {
        return """
                {
                  "itemVersion":3,
                  "sessionVersion":5,
                  "title":"Updated keyboard",
                  "description":"An updated complete auction item description",
                  "category":"ELECTRONICS",
                  "itemCondition":"LIKE_NEW",
                  "startPrice":120.00,
                  "bidIncrement":20.00,
                  "depositAmount":60.00,
                  "startAt":"2026-09-15T08:00:00Z",
                  "endAt":"2026-09-15T10:00:00Z"
                }
                """;
    }

    private static AuctionDraftTransaction.CreatedDraft createdDraft() {
        return new AuctionDraftTransaction.CreatedDraft(item(0L), session(0L), List.of());
    }

    private static AuctionDraftTransaction.UpdatedDraft updatedDraft() {
        return new AuctionDraftTransaction.UpdatedDraft(item(4L), session(6L));
    }

    private static AuctionItem item(long version) {
        return new AuctionItem(
                ITEM_ID, 42L, "Mechanical keyboard", "A complete auction item description",
                "ELECTRONICS", AuctionItemCondition.GOOD, AuctionItemReviewStatus.DRAFT,
                0, version, null, null, NOW, NOW
        );
    }

    private static AuctionSession session(long version) {
        return new AuctionSession(
                ITEM_ID + 1, ITEM_ID, 42L,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0L, NOW.plusSeconds(3600), NOW.plusSeconds(7200),
                AuctionSessionStatus.DRAFT, version, NOW, NOW
        );
    }
}

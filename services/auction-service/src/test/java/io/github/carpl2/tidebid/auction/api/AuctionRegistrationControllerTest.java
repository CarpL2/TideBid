package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionRegistrationQueryService;
import io.github.carpl2.tidebid.auction.application.AuctionRegistrationService;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuctionRegistrationController.class)
@ActiveProfiles("local-db")
@Import({AuctionSecurityConfiguration.class, CommonWebAutoConfiguration.class})
class AuctionRegistrationControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-13T08:00:00Z");
    private static final long LARGE_ID = 9_007_199_254_740_993L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuctionRegistrationService registrationService;

    @MockitoBean
    private AuctionRegistrationQueryService queryService;

    @MockitoBean
    private JwtAccessTokenVerifier tokenVerifier;

    @BeforeEach
    void authenticateToken() {
        when(tokenVerifier.verify("user-token")).thenReturn(new JwtClaims(
                "buyer", 42L, Set.of(Role.USER), NOW, NOW.plusSeconds(1800), "token-id"
        ));
    }

    @Test
    void registersTrustedUserAndReturnsOnlyPublicState() throws Exception {
        when(registrationService.register(any())).thenReturn(registration());

        String response = mockMvc.perform(post("/api/registrations")
                        .header("Authorization", "Bearer user-token")
                        .header("X-Request-Id", "register-0001")
                        .header("X-Trace-Id", "registration-trace")
                        .contentType("application/json")
                        .content("{\"auctionId\":\"9007199254740994\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "registration-trace"))
                .andExpect(jsonPath("$.data.registrationId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.auctionId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.status").value("PENDING_HOLD"))
                .andReturn().getResponse().getContentAsString();

        verify(registrationService).register(new AuctionRegistrationService.RegisterCommand(
                42L, LARGE_ID + 1, "register-0001", "registration-trace"
        ));
        assertThat(response).doesNotContain("registrationNo", "attemptCount", "nextRetryAt", "leaseOwner");
    }

    @Test
    void listsAndReadsOnlyCurrentUsersRegistrations() throws Exception {
        AuctionRegistrationQueryService.RegistrationView view = view();
        when(queryService.findMine(42L, 2, 10)).thenReturn(
                new AuctionRegistrationQueryService.PageResult(2, 10, 11, 2, List.of(view))
        );
        when(queryService.findMine(42L, LARGE_ID)).thenReturn(view);

        mockMvc.perform(get("/api/registrations/mine")
                        .header("Authorization", "Bearer user-token")
                        .queryParam("page", "2")
                        .queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[0].registrationId").value(Long.toString(LARGE_ID)));

        mockMvc.perform(get("/api/registrations/{registrationId}", Long.toString(LARGE_ID))
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registrationId").value(Long.toString(LARGE_ID)));
    }

    @Test
    void rejectsUnauthenticatedOrMalformedWritesBeforeCallingApplication() throws Exception {
        mockMvc.perform(post("/api/registrations")
                        .header("X-Request-Id", "register-0001")
                        .contentType("application/json")
                        .content("{\"auctionId\":\"88\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/registrations")
                        .header("Authorization", "Bearer user-token")
                        .header("X-Request-Id", "short")
                        .contentType("application/json")
                        .content("{\"auctionId\":\"not-a-number\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registrationService);
    }

    private static AuctionRegistration registration() {
        return new AuctionRegistration(
                LARGE_ID, "REG-SECRET-0001", LARGE_ID + 1, 42L, new BigDecimal("50.00"),
                AuctionRegistrationStatus.PENDING_HOLD, null, 1, NOW.plusSeconds(5), NOW,
                null, null, null, 1, NOW.minusSeconds(5), NOW
        );
    }

    private static AuctionRegistrationQueryService.RegistrationView view() {
        return new AuctionRegistrationQueryService.RegistrationView(
                LARGE_ID, LARGE_ID + 1, new BigDecimal("50.00"), AuctionRegistrationStatus.PENDING_HOLD,
                null, null, NOW.minusSeconds(5), NOW
        );
    }
}

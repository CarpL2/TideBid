package io.github.carpl2.tidebid.realtime.api;

import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketApplicationService;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketIssue;
import io.github.carpl2.tidebid.realtime.infrastructure.security.RealtimeSecurityConfiguration;
import io.github.carpl2.tidebid.security.InvalidAccessTokenException;
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

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        properties = {
                "tidebid.realtime.redis.enabled=true",
                "spring.cloud.nacos.config.import-check.enabled=false"
        },
        controllers = RealtimeTicketController.class
)
@ActiveProfiles("local-db")
@Import({RealtimeSecurityConfiguration.class, CommonWebAutoConfiguration.class})
class RealtimeTicketControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-21T06:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private RealtimeTicketApplicationService service;
    @MockitoBean private JwtAccessTokenVerifier tokenVerifier;

    @BeforeEach
    void authenticate() {
        when(tokenVerifier.verify("user-token")).thenReturn(new JwtClaims(
                "buyer", 42L, Set.of(Role.USER), NOW, NOW.plusSeconds(1800), "token-id"));
    }

    @Test
    void authenticatedUserCanIssueTicketWithoutExposingIdentityOrJwt() throws Exception {
        when(service.issue(any(), anyString()))
                .thenReturn(new RealtimeTicketIssue("opaque-ticket", NOW.plusSeconds(30)));

        String body = mockMvc.perform(post("/api/realtime/tickets")
                        .header("Authorization", "Bearer user-token")
                        .header("X-Trace-Id", "ticket-trace")
                        .header("X-Forwarded-For", "203.0.113.7, 127.0.0.1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "ticket-trace"))
                .andExpect(jsonPath("$.data.ticket").value("opaque-ticket"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-09-21T06:00:30Z"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("user-token", "userId", "roles");
        verify(service).issue(new io.github.carpl2.tidebid.security.AuthenticatedUser(
                42L, "buyer", Set.of(Role.USER)), "203.0.113.7");
    }

    @Test
    void missingOrInvalidJwtReturnsUniform401WithoutEchoingToken() throws Exception {
        mockMvc.perform(post("/api/realtime/tickets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED"));

        when(tokenVerifier.verify("secret-invalid-token")).thenThrow(new InvalidAccessTokenException());
        String body = mockMvc.perform(post("/api/realtime/tickets")
                        .header("Authorization", "Bearer secret-invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("secret-invalid-token");
    }

    @Test
    void redisFailureAndRateLimitUseStablePublicErrors() throws Exception {
        when(service.issue(eq(new io.github.carpl2.tidebid.security.AuthenticatedUser(
                42L, "buyer", Set.of(Role.USER))), eq("127.0.0.1")))
                .thenThrow(new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE))
                .thenThrow(new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS));

        mockMvc.perform(post("/api/realtime/tickets").header("Authorization", "Bearer user-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMMON_SERVICE_UNAVAILABLE"));
        mockMvc.perform(post("/api/realtime/tickets").header("Authorization", "Bearer user-token"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("COMMON_TOO_MANY_REQUESTS"));
    }
}

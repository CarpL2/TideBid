package io.github.carpl2.tidebid.realtime.application.service;

import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.realtime.application.port.RealtimeTicketStore;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeTicketApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T06:00:00Z");
    private static final AuthenticatedUser USER =
            new AuthenticatedUser(42L, "buyer", Set.of(Role.USER));

    private RealtimeTicketStore store;
    private RealtimeTicketApplicationService service;

    @BeforeEach
    void setUp() {
        store = mock(RealtimeTicketStore.class);
        service = new RealtimeTicketApplicationService(
                store,
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                10,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SecureRandom());
    }

    @Test
    void issuesA256BitOpaqueTicketButStoresOnlyItsDigestAndMinimalIdentity() {
        when(store.acquireRateLimit(42L, "203.0.113.7", Duration.ofSeconds(60), 10))
                .thenReturn(RealtimeTicketStore.TicketRateLimitDecision.allow());

        RealtimeTicketIssue issue = service.issue(USER, "203.0.113.7");

        assertThat(issue.ticket()).matches("[A-Za-z0-9_-]{43}");
        assertThat(issue.expiresAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(issue.toString()).doesNotContain(issue.ticket()).contains("[REDACTED]");
        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RealtimeTicketIdentity> identity = ArgumentCaptor.forClass(RealtimeTicketIdentity.class);
        verify(store).save(digest.capture(), identity.capture(), eq(Duration.ofSeconds(30)));
        assertThat(digest.getValue())
                .hasSize(64)
                .isEqualTo(RealtimeTicketApplicationService.digest(issue.ticket()))
                .doesNotContain(issue.ticket());
        assertThat(identity.getValue()).isEqualTo(new RealtimeTicketIdentity(42L, Set.of(Role.USER), NOW));
        assertThat(identity.getValue().toString()).doesNotContain(issue.ticket());
    }

    @Test
    void consumesByDigestAndRejectsMalformedTicketWithoutTouchingRedis() {
        RealtimeTicketIdentity identity = new RealtimeTicketIdentity(42L, Set.of(Role.USER), NOW);
        when(store.consume(RealtimeTicketApplicationService.digest("valid-ticket")))
                .thenReturn(Optional.of(identity));

        assertThat(service.consume("valid-ticket")).contains(identity);
        assertThat(service.consume("bad ticket")).isEmpty();
        assertThat(service.consume(" ")).isEmpty();
        verify(store).consume(RealtimeTicketApplicationService.digest("valid-ticket"));
    }

    @Test
    void rejectsUserOrIpRateLimitBeforeGeneratingOrSavingATicket() {
        when(store.acquireRateLimit(anyLong(), any(), any(), anyInt()))
                .thenReturn(RealtimeTicketStore.TicketRateLimitDecision.reject(Duration.ofSeconds(20)));

        assertThatThrownBy(() -> service.issue(USER, "203.0.113.7"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(CommonErrorCode.TOO_MANY_REQUESTS));
        verify(store, never()).save(any(), any(), any());
    }

    @Test
    void failsClosedWhenRedisIsUnavailableForRateLimitSaveOrConsume() {
        RealtimeTicketStoreUnavailableException unavailable =
                new RealtimeTicketStoreUnavailableException(new IllegalStateException("offline"));
        when(store.acquireRateLimit(anyLong(), any(), any(), anyInt())).thenThrow(unavailable);
        assertUnavailable(() -> service.issue(USER, "203.0.113.7"));

        when(store.consume(any())).thenThrow(unavailable);
        assertUnavailable(() -> service.consume("valid-ticket"));
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }
}

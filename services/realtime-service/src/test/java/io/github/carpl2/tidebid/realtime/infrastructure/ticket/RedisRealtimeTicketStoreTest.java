package io.github.carpl2.tidebid.realtime.infrastructure.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.realtime.application.port.RealtimeTicketStore;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketIdentity;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketStoreUnavailableException;
import io.github.carpl2.tidebid.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRealtimeTicketStoreTest {

    private static final String DIGEST = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-21T06:00:00Z");

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisRealtimeTicketStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new RedisRealtimeTicketStore(redisTemplate, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void savesOnlyDigestKeyMinimalJsonAndExactTtl() {
        RealtimeTicketIdentity identity = new RealtimeTicketIdentity(42L, Set.of(Role.USER, Role.ADMIN), NOW);

        store.save(DIGEST, identity, Duration.ofSeconds(30));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("tidebid:realtime:ticket:" + DIGEST), value.capture(), eq(Duration.ofSeconds(30)));
        assertThat(value.getValue())
                .contains("\"userId\":42", "\"roles\":[\"ADMIN\",\"USER\"]", "\"issuedAt\"")
                .doesNotContain("subject", "tokenId", "Authorization", "ticket");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void atomicallyConsumesOnceAndTreatsReplayOrExpiryAsMissing() {
        String stored = "{\"userId\":42,\"roles\":[\"USER\"],\"issuedAt\":\"2026-09-21T06:00:00Z\"}";
        AtomicReference<String> response = new AtomicReference<>(stored);
        org.mockito.Mockito.doAnswer(invocation -> response.getAndSet(null))
                .when(redisTemplate).execute(any(RedisScript.class), anyList());

        Optional<RealtimeTicketIdentity> first = store.consume(DIGEST);
        Optional<RealtimeTicketIdentity> replay = store.consume(DIGEST);

        assertThat(first).contains(new RealtimeTicketIdentity(42L, Set.of(Role.USER), NOW));
        assertThat(replay).isEmpty();
        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate, times(2)).execute(script.capture(), eq(List.of("tidebid:realtime:ticket:" + DIGEST)));
        assertThat(script.getAllValues().getFirst().getScriptAsString())
                .contains("redis.call('GET'", "redis.call('DEL'");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void consumesMalformedPayloadButFailsClosedInsteadOfBindingIdentity() {
        doReturn("{\"userId\":42,\"roles\":[\"ROOT\"],\"issuedAt\":\"bad\"}")
                .when(redisTemplate).execute(any(RedisScript.class), anyList());

        assertThat(store.consume(DIGEST)).isEmpty();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void limitsByBothUserAndSourceIpUsingFixedWindowLua() {
        doReturn(-1L, -1L).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(), any());

        RealtimeTicketStore.TicketRateLimitDecision decision =
                store.acquireRateLimit(42L, "203.0.113.7", Duration.ofSeconds(60), 10);

        assertThat(decision.allowed()).isTrue();
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), keys.capture(), eq("60000"), eq("10"));
        assertThat(keys.getAllValues()).containsExactly(
                List.of("tidebid:realtime:ticket-rate:user:42"),
                List.of("tidebid:realtime:ticket-rate:ip:203.0.113.7"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reportsRetryDelayAndFailsClosedOnRedisErrors() {
        doReturn(12_345L).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(), any());
        assertThat(store.acquireRateLimit(42L, "203.0.113.7", Duration.ofSeconds(60), 10))
                .isEqualTo(RealtimeTicketStore.TicketRateLimitDecision.reject(Duration.ofMillis(12_345)));

        doThrow(new QueryTimeoutException("offline")).when(valueOperations)
                .set(any(), any(), any(Duration.class));
        assertThatThrownBy(() -> store.save(
                DIGEST, new RealtimeTicketIdentity(42L, Set.of(Role.USER), NOW), Duration.ofSeconds(30)))
                .isInstanceOf(RealtimeTicketStoreUnavailableException.class);
    }
}

package io.github.carpl2.tidebid.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisAuthenticationEndpointRateLimiterTest {

    @Test
    void executesRegistrationWindowWithTheExpectedKeyAndArguments() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        RedisScript<Long> script = mock(RedisScript.class);
        GatewayRateLimitProperties properties = new GatewayRateLimitProperties(
                Duration.ofSeconds(60),
                5,
                10
        );
        when(redis.execute(
                script,
                List.of("tidebid:gateway:rate-limit:registration:127.0.0.1"),
                List.of("60000", "5")
        )).thenReturn(Flux.just(-1L));
        RedisAuthenticationEndpointRateLimiter limiter =
                new RedisAuthenticationEndpointRateLimiter(redis, script, properties);

        assertThat(limiter.acquire(GatewayRateLimitTarget.REGISTRATION, "127.0.0.1").block())
                .isEqualTo(RateLimitDecision.allow());

        verify(redis).execute(
                script,
                List.of("tidebid:gateway:rate-limit:registration:127.0.0.1"),
                List.of("60000", "5")
        );
    }

    @Test
    void convertsPositiveScriptResultToARejectionDelay() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        RedisScript<Long> script = mock(RedisScript.class);
        GatewayRateLimitProperties properties = new GatewayRateLimitProperties(
                Duration.ofSeconds(60),
                5,
                10
        );
        when(redis.execute(
                script,
                List.of("tidebid:gateway:rate-limit:login:192.0.2.20"),
                List.of("60000", "10")
        )).thenReturn(Flux.just(12_345L));
        RedisAuthenticationEndpointRateLimiter limiter =
                new RedisAuthenticationEndpointRateLimiter(redis, script, properties);

        assertThat(limiter.acquire(GatewayRateLimitTarget.LOGIN, "192.0.2.20").block())
                .isEqualTo(RateLimitDecision.reject(Duration.ofMillis(12_345)));
    }
}

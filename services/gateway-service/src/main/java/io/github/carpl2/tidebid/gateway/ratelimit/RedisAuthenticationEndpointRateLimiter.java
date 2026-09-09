package io.github.carpl2.tidebid.gateway.ratelimit;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

final class RedisAuthenticationEndpointRateLimiter implements AuthenticationEndpointRateLimiter {

    private static final String KEY_PREFIX = "tidebid:gateway:rate-limit:";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<Long> script;
    private final GatewayRateLimitProperties properties;

    RedisAuthenticationEndpointRateLimiter(
            ReactiveStringRedisTemplate redisTemplate,
            RedisScript<Long> script,
            GatewayRateLimitProperties properties
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.script = Objects.requireNonNull(script, "script must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public Mono<RateLimitDecision> acquire(GatewayRateLimitTarget target, String clientIdentifier) {
        Objects.requireNonNull(target, "target must not be null");
        if (clientIdentifier == null || clientIdentifier.isBlank()) {
            return Mono.error(new IllegalArgumentException("clientIdentifier must not be blank"));
        }

        String key = KEY_PREFIX + target.keySegment() + ":" + clientIdentifier;
        List<String> arguments = List.of(
                Long.toString(properties.window().toMillis()),
                Integer.toString(properties.maxRequests(target))
        );
        return redisTemplate.execute(script, List.of(key), arguments)
                .single()
                .map(RedisAuthenticationEndpointRateLimiter::decision);
    }

    private static RateLimitDecision decision(long scriptResult) {
        if (scriptResult == -1L) {
            return RateLimitDecision.allow();
        }
        if (scriptResult < 1L) {
            return RateLimitDecision.reject(Duration.ofMillis(1));
        }
        return RateLimitDecision.reject(Duration.ofMillis(scriptResult));
    }
}

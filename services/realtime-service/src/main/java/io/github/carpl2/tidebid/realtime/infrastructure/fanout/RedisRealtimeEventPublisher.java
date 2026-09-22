package io.github.carpl2.tidebid.realtime.infrastructure.fanout;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class RedisRealtimeEventPublisher {

    static final String EVENT_KEY_PREFIX = "tidebid:realtime:event:";
    private static final String CHANNEL_PREFIX = "tidebid:realtime:auction:";
    private static final DefaultRedisScript<Long> PUBLISH_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('SET', KEYS[1], '1', 'PX', ARGV[1])
            redis.call('PUBLISH', ARGV[2], ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration dedupTtl;

    public RedisRealtimeEventPublisher(StringRedisTemplate redisTemplate, Duration dedupTtl) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.dedupTtl = Objects.requireNonNull(dedupTtl, "dedupTtl must not be null");
        if (dedupTtl.isNegative() || dedupTtl.isZero()) {
            throw new IllegalArgumentException("dedupTtl must be positive");
        }
    }

    public boolean publish(String eventId, long auctionId, String body) {
        try {
            Long result = redisTemplate.execute(PUBLISH_SCRIPT,
                    List.of(EVENT_KEY_PREFIX + eventId),
                    Long.toString(dedupTtl.toMillis()),
                    CHANNEL_PREFIX + auctionId,
                    body);
            if (result == null) {
                throw new IllegalStateException("Redis event publish script returned null");
            }
            return result == 1L;
        } catch (DataAccessException exception) {
            throw new IllegalStateException("Redis event publish failed", exception);
        }
    }

    static String channel(long auctionId) {
        return CHANNEL_PREFIX + auctionId;
    }
}

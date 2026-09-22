package io.github.carpl2.tidebid.realtime.infrastructure.connection;

import io.github.carpl2.tidebid.realtime.application.port.RealtimeConnectionLeaseStore;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeConnectionLeaseStoreUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class RedisRealtimeConnectionLeaseStore implements RealtimeConnectionLeaseStore {

    static final String CONNECTION_KEY_PREFIX = "tidebid:realtime:connection:user:";

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local expiresAt = now + tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now)
            if redis.call('ZCARD', key) >= tonumber(ARGV[3]) then return 0 end
            redis.call('ZADD', key, expiresAt, ARGV[4])
            redis.call('EXPIRE', key, math.ceil(tonumber(ARGV[2]) / 1000))
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            redis.call('ZREM', key, ARGV[1])
            if redis.call('ZCARD', key) == 0 then redis.call('DEL', key) end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisRealtimeConnectionLeaseStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
    }

    @Override
    public boolean acquire(long userId, String connectionId, int maxConnections, Duration ttl) {
        try {
            Long result = redisTemplate.execute(
                    ACQUIRE_SCRIPT,
                    List.of(userKey(userId)),
                    Long.toString(System.currentTimeMillis()),
                    Long.toString(ttl.toMillis()),
                    Integer.toString(maxConnections),
                    connectionId);
            if (result == null) {
                throw new RealtimeConnectionLeaseStoreUnavailableException(
                        new IllegalStateException("Redis lease script returned null"));
            }
            return result == 1L;
        } catch (DataAccessException exception) {
            throw new RealtimeConnectionLeaseStoreUnavailableException(exception);
        }
    }

    @Override
    public void release(long userId, String connectionId) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(userKey(userId)), connectionId);
        } catch (DataAccessException exception) {
            throw new RealtimeConnectionLeaseStoreUnavailableException(exception);
        }
    }

    private static String userKey(long userId) {
        return CONNECTION_KEY_PREFIX + userId;
    }
}

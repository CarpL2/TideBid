package io.github.carpl2.tidebid.realtime.infrastructure.ticket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.realtime.application.port.RealtimeTicketStore;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketIdentity;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketStoreUnavailableException;
import io.github.carpl2.tidebid.security.Role;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class RedisRealtimeTicketStore implements RealtimeTicketStore {

    static final String TICKET_KEY_PREFIX = "tidebid:realtime:ticket:";
    static final String USER_RATE_LIMIT_KEY_PREFIX = "tidebid:realtime:ticket-rate:user:";
    static final String IP_RATE_LIMIT_KEY_PREFIX = "tidebid:realtime:ticket-rate:ip:";

    private static final DefaultRedisScript<Long> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            local ttl = redis.call('PTTL', KEYS[1])
            if count == 1 or ttl < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
                ttl = tonumber(ARGV[1])
            end
            if count <= tonumber(ARGV[2]) then return -1 end
            if ttl < 1 then return 1 end
            return ttl
            """, Long.class);
    private static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if value then redis.call('DEL', KEYS[1]) end
            return value
            """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisRealtimeTicketStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public TicketRateLimitDecision acquireRateLimit(long userId, String sourceIp, Duration window, int maxRequests) {
        TicketRateLimitDecision userDecision = acquire(
                USER_RATE_LIMIT_KEY_PREFIX + userId, window, maxRequests);
        if (!userDecision.allowed()) {
            return userDecision;
        }
        return acquire(IP_RATE_LIMIT_KEY_PREFIX + sourceIp, window, maxRequests);
    }

    @Override
    public void save(String digest, RealtimeTicketIdentity identity, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(ticketKey(digest), serialize(identity), ttl);
        } catch (DataAccessException exception) {
            throw new RealtimeTicketStoreUnavailableException(exception);
        }
    }

    @Override
    public Optional<RealtimeTicketIdentity> consume(String digest) {
        try {
            String value = redisTemplate.execute(CONSUME_SCRIPT, List.of(ticketKey(digest)));
            if (value == null) {
                return Optional.empty();
            }
            return deserialize(value);
        } catch (DataAccessException exception) {
            throw new RealtimeTicketStoreUnavailableException(exception);
        }
    }

    private TicketRateLimitDecision acquire(String key, Duration window, int maxRequests) {
        try {
            Long result = redisTemplate.execute(
                    FIXED_WINDOW_SCRIPT,
                    List.of(key),
                    Long.toString(window.toMillis()),
                    Integer.toString(maxRequests));
            if (result == null) {
                throw new RealtimeTicketStoreUnavailableException(
                        new IllegalStateException("Redis rate limit script returned null"));
            }
            if (result == -1L) {
                return TicketRateLimitDecision.allow();
            }
            return TicketRateLimitDecision.reject(Duration.ofMillis(Math.max(result, 1L)));
        } catch (DataAccessException exception) {
            throw new RealtimeTicketStoreUnavailableException(exception);
        }
    }

    private String serialize(RealtimeTicketIdentity identity) {
        try {
            List<String> roles = identity.roles().stream().map(Role::name).sorted().toList();
            return objectMapper.writeValueAsString(new StoredTicket(identity.userId(), roles, identity.issuedAt()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize realtime ticket identity", exception);
        }
    }

    private Optional<RealtimeTicketIdentity> deserialize(String value) {
        try {
            StoredTicket stored = objectMapper.readValue(value, StoredTicket.class);
            if (stored.userId() <= 0 || stored.roles() == null || stored.roles().isEmpty() || stored.issuedAt() == null) {
                return Optional.empty();
            }
            return Optional.of(new RealtimeTicketIdentity(
                    stored.userId(),
                    stored.roles().stream().map(Role::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    stored.issuedAt()));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static String ticketKey(String digest) {
        return TICKET_KEY_PREFIX + digest;
    }

    private record StoredTicket(long userId, List<String> roles, Instant issuedAt) { }
}

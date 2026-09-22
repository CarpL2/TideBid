package io.github.carpl2.tidebid.realtime.infrastructure.fanout;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class RedisRealtimeEventPublisherTest {

    @Test
    void publishesOnlyWhenTheAtomicScriptClaimsTheEvent() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(1L, 0L);
        RedisRealtimeEventPublisher publisher = new RedisRealtimeEventPublisher(redis, java.time.Duration.ofHours(2));

        assertThat(publisher.publish("event-1", 42L, "{}"))
                .isTrue();
        assertThat(publisher.publish("event-1", 42L, "{}"))
                .isFalse();
        verify(redis, times(2)).execute(any(RedisScript.class), eq(List.of("tidebid:realtime:event:event-1")),
                eq("7200000"), eq("tidebid:realtime:auction:42"), eq("{}"));
    }
}

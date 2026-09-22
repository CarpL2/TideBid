package io.github.carpl2.tidebid.realtime.infrastructure.connection;

import io.github.carpl2.tidebid.realtime.application.port.RealtimeConnectionLeaseStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tidebid.realtime.redis", name = "enabled", havingValue = "true")
public class RealtimeConnectionLeaseConfiguration {

    @Bean
    RealtimeConnectionLeaseStore realtimeConnectionLeaseStore(StringRedisTemplate redisTemplate) {
        return new RedisRealtimeConnectionLeaseStore(redisTemplate);
    }
}

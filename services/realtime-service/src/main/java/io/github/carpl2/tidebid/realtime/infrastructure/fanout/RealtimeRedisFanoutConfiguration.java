package io.github.carpl2.tidebid.realtime.infrastructure.fanout;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tidebid.realtime.redis", name = "enabled", havingValue = "true")
public class RealtimeRedisFanoutConfiguration {

    @Bean
    RedisRealtimeEventPublisher realtimeEventPublisher(StringRedisTemplate redisTemplate,
                                                       RealtimeProperties properties) {
        return new RedisRealtimeEventPublisher(redisTemplate, properties.redis().eventDedupTtl());
    }

    @Bean
    RealtimeWebSocketSessionRegistry realtimeWebSocketSessionRegistry(ObjectMapper objectMapper) {
        return new RealtimeWebSocketSessionRegistry(objectMapper);
    }

    @Bean
    RealtimeRedisMessageListener realtimeRedisMessageListener(
            ObjectMapper objectMapper,
            RealtimeWebSocketSessionRegistry registry
    ) {
        return new RealtimeRedisMessageListener(objectMapper, registry);
    }

    @Bean
    RedisMessageListenerContainer realtimeRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RealtimeRedisMessageListener listener
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new PatternTopic("tidebid:realtime:auction:*"));
        return container;
    }
}

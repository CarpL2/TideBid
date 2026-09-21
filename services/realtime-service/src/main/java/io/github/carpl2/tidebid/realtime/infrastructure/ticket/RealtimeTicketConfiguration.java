package io.github.carpl2.tidebid.realtime.infrastructure.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.realtime.application.port.RealtimeTicketStore;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketApplicationService;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tidebid.realtime.redis", name = "enabled", havingValue = "true")
public class RealtimeTicketConfiguration {

    @Bean
    RealtimeTicketStore realtimeTicketStore(
            org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        return new RedisRealtimeTicketStore(redisTemplate, objectMapper);
    }

    @Bean
    RealtimeTicketApplicationService realtimeTicketApplicationService(
            RealtimeTicketStore ticketStore,
            RealtimeProperties properties,
            Clock clock
    ) {
        return new RealtimeTicketApplicationService(
                ticketStore,
                properties.ticket().ttl(),
                properties.ticket().rateLimitWindow(),
                properties.ticket().rateLimitMaxRequests(),
                clock,
                new SecureRandom());
    }
}

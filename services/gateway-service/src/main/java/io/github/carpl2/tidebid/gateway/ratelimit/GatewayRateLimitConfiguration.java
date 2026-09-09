package io.github.carpl2.tidebid.gateway.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration(proxyBeanMethods = false)
@Profile("nacos")
@EnableConfigurationProperties(GatewayRateLimitProperties.class)
public class GatewayRateLimitConfiguration {

    @Bean
    RedisScript<Long> gatewayFixedWindowRateLimitScript() {
        return RedisScript.of(
                new ClassPathResource("META-INF/scripts/gateway-fixed-window.lua"),
                Long.class
        );
    }

    @Bean
    AuthenticationEndpointRateLimiter authenticationEndpointRateLimiter(
            ReactiveStringRedisTemplate redisTemplate,
            RedisScript<Long> gatewayFixedWindowRateLimitScript,
            GatewayRateLimitProperties properties
    ) {
        return new RedisAuthenticationEndpointRateLimiter(
                redisTemplate,
                gatewayFixedWindowRateLimitScript,
                properties
        );
    }
}

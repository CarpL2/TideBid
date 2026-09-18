package io.github.carpl2.tidebid.trade.infrastructure.persistence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class TradePersistenceConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock utcClock() {
        return Clock.systemUTC();
    }
}

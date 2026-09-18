package io.github.carpl2.tidebid.trade.infrastructure.client;

import feign.Logger;
import feign.RequestInterceptor;
import feign.Retryer;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeAccountClientProperties;
import org.springframework.context.annotation.Bean;

class AccountDebitFeignConfiguration {

    @Bean
    RequestInterceptor internalToken(TradeAccountClientProperties properties) {
        String token = properties.requiredInternalToken();
        return template -> {
            template.removeHeader(SecurityHeaders.INTERNAL_SERVICE_TOKEN);
            template.header(SecurityHeaders.INTERNAL_SERVICE_TOKEN, token);
        };
    }

    @Bean Retryer retryer() { return Retryer.NEVER_RETRY; }
    @Bean Logger.Level loggerLevel() { return Logger.Level.NONE; }
}

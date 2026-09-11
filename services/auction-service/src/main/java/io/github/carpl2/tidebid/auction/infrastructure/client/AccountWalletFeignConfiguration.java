package io.github.carpl2.tidebid.auction.infrastructure.client;

import feign.Logger;
import feign.RequestInterceptor;
import feign.Retryer;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionAccountClientProperties;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.springframework.context.annotation.Bean;

class AccountWalletFeignConfiguration {

    @Bean
    RequestInterceptor accountInternalTokenInterceptor(AuctionAccountClientProperties properties) {
        String token = properties.requiredInternalToken();
        return template -> {
            template.removeHeader(SecurityHeaders.INTERNAL_SERVICE_TOKEN);
            template.header(SecurityHeaders.INTERNAL_SERVICE_TOKEN, token);
        };
    }

    @Bean
    Retryer accountWalletRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    Logger.Level accountWalletFeignLoggerLevel() {
        return Logger.Level.NONE;
    }
}

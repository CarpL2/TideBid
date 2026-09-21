package io.github.carpl2.tidebid.realtime.infrastructure.client;

import feign.Logger;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeProperties;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

class AuctionSnapshotFeignConfiguration {

    @Bean
    RequestInterceptor auctionInternalToken(RealtimeProperties properties) {
        String token = properties.auctionClient().requiredInternalToken();
        return template -> {
            template.removeHeader(SecurityHeaders.INTERNAL_SERVICE_TOKEN);
            template.header(SecurityHeaders.INTERNAL_SERVICE_TOKEN, token);
        };
    }

    @Bean
    Request.Options auctionSnapshotOptions(RealtimeProperties properties) {
        return new Request.Options(
                properties.auctionClient().connectTimeout().toMillis(), TimeUnit.MILLISECONDS,
                properties.auctionClient().readTimeout().toMillis(), TimeUnit.MILLISECONDS,
                true);
    }

    @Bean Retryer auctionSnapshotRetryer() { return Retryer.NEVER_RETRY; }
    @Bean Logger.Level auctionSnapshotLoggerLevel() { return Logger.Level.NONE; }
}

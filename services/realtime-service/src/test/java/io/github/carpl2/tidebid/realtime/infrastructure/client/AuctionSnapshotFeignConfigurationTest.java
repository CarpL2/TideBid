package io.github.carpl2.tidebid.realtime.infrastructure.client;

import feign.RequestTemplate;
import feign.Retryer;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeProperties;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimePropertiesTest;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuctionSnapshotFeignConfigurationTest {

    @Test
    void addsOnlyTheConfiguredInternalTokenAndDisablesRetriesAndBodyLogging() {
        RealtimeProperties properties = RealtimePropertiesTest.validProperties();
        AuctionSnapshotFeignConfiguration configuration = new AuctionSnapshotFeignConfiguration();
        RequestTemplate template = new RequestTemplate();

        configuration.auctionInternalToken(properties).apply(template);

        assertThat(template.headers().get(SecurityHeaders.INTERNAL_SERVICE_TOKEN))
                .containsExactly("x".repeat(32));
        assertThat(configuration.auctionSnapshotRetryer()).isSameAs(Retryer.NEVER_RETRY);
        assertThat(configuration.auctionSnapshotLoggerLevel()).isEqualTo(feign.Logger.Level.NONE);
        assertThat(configuration.auctionSnapshotOptions(properties).connectTimeoutMillis()).isEqualTo(2000);
        assertThat(configuration.auctionSnapshotOptions(properties).readTimeoutMillis()).isEqualTo(3000);
    }
}

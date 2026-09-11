package io.github.carpl2.tidebid.auction.infrastructure.client;

import feign.Logger;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Retryer;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionAccountClientProperties;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;

import static org.assertj.core.api.Assertions.assertThat;

class AccountWalletFeignConfigurationTest {

    @Test
    void resolvesAccountByNacosServiceNameWithoutFixedUrl() {
        FeignClient annotation = AccountWalletFeignClient.class.getAnnotation(FeignClient.class);

        assertThat(annotation.name()).isEqualTo("tidebid-account");
        assertThat(annotation.url()).isEmpty();
    }

    @Test
    void replacesCallerTokenWithConfiguredInternalToken() {
        String token = "test-internal-token-with-at-least-32-characters";
        AccountWalletFeignConfiguration configuration = new AccountWalletFeignConfiguration();
        RequestInterceptor interceptor = configuration.accountInternalTokenInterceptor(
                new AuctionAccountClientProperties(token)
        );
        RequestTemplate template = new RequestTemplate();
        template.header(SecurityHeaders.INTERNAL_SERVICE_TOKEN, "untrusted-caller-value");

        interceptor.apply(template);

        assertThat(template.headers().get(SecurityHeaders.INTERNAL_SERVICE_TOKEN))
                .containsExactly(token);
    }

    @Test
    void disablesAutomaticRetriesAndFeignBodyLogging() {
        AccountWalletFeignConfiguration configuration = new AccountWalletFeignConfiguration();

        assertThat(configuration.accountWalletRetryer()).isSameAs(Retryer.NEVER_RETRY);
        assertThat(configuration.accountWalletFeignLoggerLevel()).isEqualTo(Logger.Level.NONE);
    }
}

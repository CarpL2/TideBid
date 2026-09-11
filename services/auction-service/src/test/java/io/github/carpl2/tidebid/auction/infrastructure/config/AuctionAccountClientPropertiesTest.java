package io.github.carpl2.tidebid.auction.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionAccountClientPropertiesTest {

    @Test
    void acceptsConfiguredTokenAndNeverPrintsIt() {
        String token = "test-internal-token-with-at-least-32-characters";
        AuctionAccountClientProperties properties = new AuctionAccountClientProperties(token);

        assertThat(properties.requiredInternalToken()).isEqualTo(token);
        assertThat(properties.toString())
                .contains("[REDACTED]")
                .doesNotContain(token);
    }

    @Test
    void rejectsMissingOrShortTokenWhenFeignClientStarts() {
        assertThatThrownBy(() -> new AuctionAccountClientProperties("").requiredInternalToken())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TIDEBID_INTERNAL_SERVICE_TOKEN");
        assertThatThrownBy(() -> new AuctionAccountClientProperties("too-short").requiredInternalToken())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 to 512");
    }
}

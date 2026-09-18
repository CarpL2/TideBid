package io.github.carpl2.tidebid.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRouteConfigurationTest {

    private static final String ROUTES = "spring.cloud.gateway.server.webflux.routes";

    @Test
    void nacosRoutesKeepAuctionAdminAheadOfTheNarrowAccountAdminRoute() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "tidebid-gateway",
                new FileSystemResource(findRepositoryRoot().resolve("infra/nacos/configs/tidebid-gateway.yml"))
        );

        assertThat(property(sources, ROUTES + "[0].id")).isEqualTo("auction-admin-assets");
        assertThat(property(sources, ROUTES + "[0].uri")).isEqualTo("lb://tidebid-auction");
        assertThat(property(sources, ROUTES + "[0].predicates[0]"))
                .isEqualTo("Path=/api/admin/assets/**");

        assertThat(property(sources, ROUTES + "[1].id")).isEqualTo("account-service");
        assertThat(property(sources, ROUTES + "[1].uri")).isEqualTo("lb://tidebid-account");
        assertThat(property(sources, ROUTES + "[1].predicates[0]"))
                .isEqualTo("Path=/api/auth/**,/api/users/**,/api/wallets/**,/api/admin/access-check");

        assertThat(property(sources, ROUTES + "[2].id")).isEqualTo("auction-service");
        assertThat(property(sources, ROUTES + "[2].uri")).isEqualTo("lb://tidebid-auction");
        assertThat(property(sources, ROUTES + "[2].predicates[0]"))
                .isEqualTo("Path=/api/assets/**,/api/auctions/**,/api/bids/**,/api/registrations/**");

        assertThat(property(sources, ROUTES + "[3].id")).isEqualTo("trade-service");
        assertThat(property(sources, ROUTES + "[3].uri")).isEqualTo("lb://tidebid-trade");
        assertThat(property(sources, ROUTES + "[3].predicates[0]"))
                .isEqualTo("Path=/api/orders/**,/api/payments/**");
    }

    private static Object property(List<PropertySource<?>> sources, String name) {
        return sources.stream()
                .map(source -> source.getProperty(name))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("infra/nacos/configs/tidebid-gateway.yml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate TideBid repository root");
    }
}

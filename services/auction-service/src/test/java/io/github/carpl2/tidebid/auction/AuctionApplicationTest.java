package io.github.carpl2.tidebid.auction;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionImageProperties;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionRegistrationRecoveryProperties;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionStorageProperties;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionTimingProperties;
import io.github.carpl2.tidebid.auction.infrastructure.storage.UnconfiguredObjectStorageAdapter;
import io.github.carpl2.tidebid.web.GlobalExceptionHandler;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "tidebid.auction.storage.enabled=false",
                "tidebid.auction.storage.endpoint=",
                "tidebid.auction.storage.region=",
                "tidebid.auction.storage.bucket=",
                "tidebid.auction.storage.access-key-id=",
                "tidebid.auction.storage.access-key-secret="
        }
)
@ActiveProfiles("standalone")
class AuctionApplicationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private AuctionStorageProperties storageProperties;

    @Autowired
    private AuctionImageProperties imageProperties;

    @Autowired
    private AuctionTimingProperties timingProperties;

    @Autowired
    private AuctionRegistrationRecoveryProperties recoveryProperties;

    @Test
    void servesHealthAndIdentity() {
        ResponseEntity<JsonNode> health = client.getForEntity("/actuator/health", JsonNode.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).isNotNull();
        assertThat(health.getBody().path("status").asText()).isEqualTo("UP");
        assertThat(health.getBody().has("components")).isFalse();

        ResponseEntity<JsonNode> info = client.getForEntity("/actuator/info", JsonNode.class);
        assertThat(info.getStatusCode().value()).isEqualTo(200);
        assertThat(info.getBody()).isNotNull();
        assertThat(info.getBody().path("app").path("name").asText()).isEqualTo("tidebid-auction");
        assertThat(context.getBeansOfType(TraceIdFilter.class)).hasSize(1);
        assertThat(context.getBeansOfType(GlobalExceptionHandler.class)).hasSize(1);
        assertThat(environment.getProperty("spring.cloud.nacos.discovery.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.cloud.nacos.config.enabled", Boolean.class)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/env", "/actuator/beans", "/does-not-exist"})
    void rejectsUnexposedEndpointsWithUniformJson(String path) {
        ResponseEntity<JsonNode> response = client.getForEntity(path, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("COMMON_NOT_FOUND");
        assertThat(response.getBody().path("traceId").asText())
                .isEqualTo(response.getHeaders().getFirst("X-Trace-Id"))
                .matches("[a-f0-9]{32}");
    }

    @Test
    void bindsSafeAuctionCoreDefaultsWithoutCloudCredentials() {
        assertThat(storageProperties.enabled()).isFalse();
        assertThat(storageProperties.endpoint()).isEmpty();
        assertThat(storageProperties.accessKeyId()).isEmpty();
        assertThat(storageProperties.accessKeySecret()).isEmpty();
        assertThat(storageProperties.objectKeyPrefix()).isEqualTo("dev");
        assertThat(storageProperties.uploadUrlTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(storageProperties.readUrlTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(storageProperties.pendingRetention()).isEqualTo(Duration.ofHours(24));
        assertThat(context.getBeansOfType(UnconfiguredObjectStorageAdapter.class)).hasSize(1);

        assertThat(imageProperties.allowedContentTypes())
                .containsExactlyInAnyOrder("image/jpeg", "image/png", "image/webp");
        assertThat(imageProperties.maxSize()).isEqualTo(DataSize.ofMegabytes(10));
        assertThat(imageProperties.maxImagesPerItem()).isEqualTo(9);

        assertThat(timingProperties.minimumLeadTime()).isEqualTo(Duration.ofMinutes(1));
        assertThat(timingProperties.maximumDuration()).isEqualTo(Duration.ofDays(7));
        assertThat(timingProperties.openingScanEnabled()).isTrue();
        assertThat(timingProperties.openingScanInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(timingProperties.openingScanBatchSize()).isEqualTo(50);

        assertThat(recoveryProperties.initialRetryDelay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(recoveryProperties.maximumRetryDelay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(recoveryProperties.leaseDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(recoveryProperties.batchSize()).isEqualTo(50);
    }
}

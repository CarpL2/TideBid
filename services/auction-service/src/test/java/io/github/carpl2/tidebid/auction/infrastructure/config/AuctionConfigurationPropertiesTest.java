package io.github.carpl2.tidebid.auction.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionConfigurationPropertiesTest {

    @Test
    void allowsStorageToBeDisabledWithoutCredentialsAndRedactsConfiguredSecrets() {
        AuctionStorageProperties disabled = storage(false, "", "", "", "", "");
        assertThat(disabled.enabled()).isFalse();

        AuctionStorageProperties enabled = storage(
                true,
                "https://oss-cn-beijing.aliyuncs.com",
                "cn-beijing",
                "tidebid-dev",
                "example-access-key-id",
                "example-access-key-secret"
        );
        assertThat(enabled.toString())
                .contains("accessKeyId=[REDACTED]", "accessKeySecret=[REDACTED]")
                .doesNotContain("example-access-key-id", "example-access-key-secret");
    }

    @Test
    void rejectsIncompleteOrUnsafeEnabledStorageConfiguration() {
        assertThatThrownBy(() -> storage(true, "", "cn-beijing", "tidebid-dev", "id", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint");
        assertThatThrownBy(() -> storage(true, "ftp://example.com", "cn-beijing", "tidebid-dev", "id", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP(S)");
        assertThatThrownBy(() -> storage(true, "https://oss-cn-beijing.aliyuncs.com", "CN_BEIJING", "tidebid-dev", "id", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("region");
        assertThatThrownBy(() -> storage(true, "https://oss-cn-beijing.aliyuncs.com", "cn-beijing", "Invalid_Bucket", "id", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucket");
    }

    @Test
    void rejectsUnsafeImageTimingAndRecoveryLimits() {
        assertThatThrownBy(() -> new AuctionImageProperties(
                Set.of("text/plain"), DataSize.ofMegabytes(10), 9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image MIME");
        assertThatThrownBy(() -> new AuctionImageProperties(
                Set.of("image/jpeg"), DataSize.ofMegabytes(101), 9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100MB");
        assertThatThrownBy(() -> new AuctionTimingProperties(
                Duration.ofMinutes(10), Duration.ofMinutes(5), true, Duration.ofSeconds(1), 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than");
        assertThatThrownBy(() -> new AuctionTimingProperties(
                Duration.ofMinutes(1), Duration.ofDays(7), true, Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openingScanBatchSize");
        assertThatThrownBy(() -> new AuctionRegistrationRecoveryProperties(
                Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofSeconds(30), 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be shorter");
        assertThatThrownBy(() -> new AuctionRegistrationRecoveryProperties(
                Duration.ofSeconds(5), Duration.ofMinutes(5), Duration.ofSeconds(30), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
        assertThatThrownBy(() -> new AuctionImageCleanupProperties(Duration.ofSeconds(5), 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scanInterval");
        assertThatThrownBy(() -> new AuctionImageCleanupProperties(Duration.ofMinutes(1), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    private static AuctionStorageProperties storage(
            boolean enabled,
            String endpoint,
            String region,
            String bucket,
            String accessKeyId,
            String accessKeySecret
    ) {
        return new AuctionStorageProperties(
                enabled,
                endpoint,
                region,
                bucket,
                accessKeyId,
                accessKeySecret,
                "dev",
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                Duration.ofHours(24)
        );
    }
}

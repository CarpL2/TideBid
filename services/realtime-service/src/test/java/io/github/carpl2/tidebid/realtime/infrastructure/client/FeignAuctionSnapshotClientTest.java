package io.github.carpl2.tidebid.realtime.infrastructure.client;

import io.github.carpl2.tidebid.contracts.RealtimeAuctionStatus;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.realtime.infrastructure.metrics.RealtimeMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeignAuctionSnapshotClientTest {

    @Test
    void mapsTrustedPersonalizedSnapshotWithoutLeakingInternalIds() {
        AuctionSnapshotFeignClient feign = mock(AuctionSnapshotFeignClient.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FeignAuctionSnapshotClient client = new FeignAuctionSnapshotClient(feign, new RealtimeMetrics(registry));
        Instant now = Instant.parse("2026-09-21T06:00:00Z");
        AuctionSnapshotHttpResponse payload = new AuctionSnapshotHttpResponse(
                "9007199254740994", "OPEN", new BigDecimal("210.00"), new BigDecimal("220.00"),
                2, now.plusSeconds(300), 1, null, 2, true, true, now,
                List.of(new AuctionSnapshotHttpResponse.Bid(
                        "9007199254740993", new BigDecimal("210.00"), new BigDecimal("200.00"),
                        2, true, now)));
        when(feign.find("9007199254740994", 1, 100, "42", "trace-1234"))
                .thenReturn(ApiResponse.success(payload, "trace-1234"));

        var result = client.find(9_007_199_254_740_994L, 1, 100, 42L, "trace-1234");

        assertThat(result.status()).isEqualTo(RealtimeAuctionStatus.OPEN);
        assertThat(result.leading()).isTrue();
        assertThat(result.proxyActive()).isTrue();
        assertThat(result.bids()).singleElement().satisfies(bid -> {
            assertThat(bid.bidId()).isEqualTo(9_007_199_254_740_993L);
            assertThat(bid.mine()).isTrue();
        });
        assertThat(registry.get("tidebid.realtime.snapshot.requests").tag("outcome", "success")
                .counter().count()).isEqualTo(1.0);
        verify(feign).find("9007199254740994", 1, 100, "42", "trace-1234");
    }

    @Test
    void rejectsMismatchedAuctionAndCountsFailure() {
        AuctionSnapshotFeignClient feign = mock(AuctionSnapshotFeignClient.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FeignAuctionSnapshotClient client = new FeignAuctionSnapshotClient(feign, new RealtimeMetrics(registry));
        Instant now = Instant.parse("2026-09-21T06:00:00Z");
        when(feign.find("101", 0, 10, "42", "trace-1234")).thenReturn(ApiResponse.success(
                new AuctionSnapshotHttpResponse(
                        "102", "OPEN", new BigDecimal("100.00"), new BigDecimal("110.00"),
                        0, now.plusSeconds(300), 0, null, 0, false, false, now, List.of()),
                "trace-1234"));

        assertThatThrownBy(() -> client.find(101L, 0, 10, 42L, "trace-1234"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
        assertThat(registry.get("tidebid.realtime.snapshot.requests").tag("outcome", "failure")
                .counter().count()).isEqualTo(1.0);
    }
}

package io.github.carpl2.tidebid.realtime.infrastructure.client;

import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        contextId = "auctionSnapshotFeignClient",
        name = "tidebid-auction",
        url = "${tidebid.realtime.auction-client.base-url:}",
        configuration = AuctionSnapshotFeignConfiguration.class
)
interface AuctionSnapshotFeignClient {

    @GetMapping("/internal/realtime/auctions/{auctionId}/snapshot")
    ApiResponse<AuctionSnapshotHttpResponse> find(
            @PathVariable("auctionId") String auctionId,
            @RequestParam("afterSequenceNo") long afterSequenceNo,
            @RequestParam("limit") int limit,
            @RequestHeader(SecurityHeaders.INTERNAL_USER_ID) String userId,
            @RequestHeader(SecurityHeaders.TRACE_ID) String traceId
    );
}

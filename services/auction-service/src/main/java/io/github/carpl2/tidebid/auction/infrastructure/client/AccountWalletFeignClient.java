package io.github.carpl2.tidebid.auction.infrastructure.client;

import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(
        contextId = "accountWalletFeignClient",
        name = "tidebid-account",
        url = "${tidebid.auction.account-client.base-url:}",
        configuration = AccountWalletFeignConfiguration.class
)
interface AccountWalletFeignClient {

    @PostMapping("/internal/wallet-holds")
    ApiResponse<AccountWalletHoldResponse> hold(
            @RequestHeader(SecurityHeaders.REQUEST_ID) String requestId,
            @RequestHeader(SecurityHeaders.TRACE_ID) String traceId,
            @RequestBody AccountWalletHoldRequest request
    );

    @GetMapping("/internal/wallet-holds/{holdNo}")
    ApiResponse<AccountWalletHoldResponse> findByHoldNo(
            @PathVariable("holdNo") String holdNo,
            @RequestHeader(SecurityHeaders.TRACE_ID) String traceId
    );
}

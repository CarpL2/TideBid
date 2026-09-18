package io.github.carpl2.tidebid.trade.infrastructure.client;

import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
        contextId = "tradeAccountDebitFeignClient",
        name = "tidebid-account",
        url = "${tidebid.trade.account-client.base-url:}",
        configuration = AccountDebitFeignConfiguration.class
)
interface AccountDebitFeignClient {

    @PostMapping("/internal/wallet-debits")
    ApiResponse<AccountDebitResponse> debit(
            @RequestHeader(SecurityHeaders.REQUEST_ID) String requestId,
            @RequestHeader(SecurityHeaders.TRACE_ID) String traceId,
            @RequestBody AccountDebitRequest request
    );

    @GetMapping("/internal/wallet-debits/{paymentNo}")
    ApiResponse<AccountDebitResponse> find(
            @PathVariable("paymentNo") String paymentNo,
            @RequestHeader(SecurityHeaders.TRACE_ID) String traceId
    );
}

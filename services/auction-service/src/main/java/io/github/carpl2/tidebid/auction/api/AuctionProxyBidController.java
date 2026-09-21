package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionProxyBidApplicationService;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auctions/{auctionId}/proxy-bid")
@Validated
@Profile({"local-db", "nacos"})
public class AuctionProxyBidController {
    private final AuctionProxyBidApplicationService service;

    public AuctionProxyBidController(AuctionProxyBidApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AuctionProxyBidResponse.Detail> findMine(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @PathVariable String auctionId,
            HttpServletRequest request
    ) {
        return ApiResponse.success(AuctionProxyBidResponse.Detail.from(
                service.findMine(identity.userId(), parseId(auctionId)).orElse(null)), traceId(request));
    }

    @PutMapping
    public ApiResponse<AuctionProxyBidResponse.Result> upsert(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @PathVariable String auctionId,
            @RequestHeader(SecurityHeaders.REQUEST_ID)
            @Pattern(regexp = "[A-Za-z0-9_-]{8,48}") String requestId,
            @Valid @RequestBody UpsertAuctionProxyBidRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.success(AuctionProxyBidResponse.Result.from(
                service.upsert(identity.userId(), parseId(auctionId), requestId, body.maxAmount())), traceId(request));
    }

    @DeleteMapping
    public ApiResponse<AuctionProxyBidResponse.Result> disable(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @PathVariable String auctionId,
            @RequestHeader(SecurityHeaders.REQUEST_ID)
            @Pattern(regexp = "[A-Za-z0-9_-]{8,48}") String requestId,
            HttpServletRequest request
    ) {
        return ApiResponse.success(AuctionProxyBidResponse.Result.from(
                service.disable(identity.userId(), parseId(auctionId), requestId)), traceId(request));
    }

    private static long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) {
            throw new BusinessException(AuctionErrorCode.AUCTION_INVALID,
                    "auctionId must be a positive integer");
        }
    }

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return value instanceof String trace && io.github.carpl2.tidebid.core.TraceIds.isValid(trace)
                ? trace : io.github.carpl2.tidebid.core.TraceIds.create();
    }
}

package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionDetailQueryService;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auctions")
@Profile({"local-db", "nacos"})
public class AuctionDetailController {

    private final AuctionDetailQueryService queryService;

    public AuctionDetailController(AuctionDetailQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{auctionId}")
    public ApiResponse<AuctionDetailResponse> findDetail(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @PathVariable String auctionId,
            HttpServletRequest request
    ) {
        return ApiResponse.success(
                AuctionDetailResponse.from(queryService.find(identity.userId(), parseId(auctionId))),
                traceId(request)
        );
    }

    private static long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new NumberFormatException("non-positive ID");
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new BusinessException(AuctionErrorCode.AUCTION_INVALID, "auctionId must be a positive integer");
        }
    }

    private static String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (traceId instanceof String value && TraceIds.isValid(value)) {
            return value;
        }
        return TraceIds.create();
    }
}

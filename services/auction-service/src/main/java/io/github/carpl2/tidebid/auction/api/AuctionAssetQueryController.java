package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionAssetQueryService;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.security.Role;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assets")
@Profile({"local-db", "nacos"})
public class AuctionAssetQueryController {

    private final AuctionAssetQueryService queryService;

    public AuctionAssetQueryController(AuctionAssetQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/mine")
    public ApiResponse<AuctionAssetResponse.Page> findMine(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request
    ) {
        return ApiResponse.success(
                AuctionAssetResponse.Page.from(queryService.findMine(identity.userId(), page, size)),
                traceId(request)
        );
    }

    @GetMapping("/{assetId}")
    public ApiResponse<AuctionAssetResponse.Detail> findDetail(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @PathVariable String assetId,
            HttpServletRequest request
    ) {
        long parsedAssetId = parseId(assetId);
        return ApiResponse.success(
                AuctionAssetResponse.Detail.from(queryService.findDetail(
                        identity.userId(), identity.hasRole(Role.ADMIN), parsedAssetId
                )),
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
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "assetId must be a positive integer");
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

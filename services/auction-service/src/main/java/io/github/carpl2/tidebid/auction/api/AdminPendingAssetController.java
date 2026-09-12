package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionAssetQueryService;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.security.Role;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/assets")
@Profile({"local-db", "nacos"})
public class AdminPendingAssetController {

    private final AuctionAssetQueryService queryService;

    public AdminPendingAssetController(AuctionAssetQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/pending")
    public ApiResponse<AdminPendingAssetResponse.Page> findPending(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request
    ) {
        return ApiResponse.success(
                AdminPendingAssetResponse.Page.from(
                        queryService.findPendingReviews(identity.hasRole(Role.ADMIN), page, size)
                ),
                traceId(request)
        );
    }

    private static String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (traceId instanceof String value && TraceIds.isValid(value)) {
            return value;
        }
        return TraceIds.create();
    }
}

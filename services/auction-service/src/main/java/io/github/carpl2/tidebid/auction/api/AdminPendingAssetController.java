package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionAssetQueryService;
import io.github.carpl2.tidebid.auction.application.AuctionReviewService;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionReviewDecision;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.security.Role;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/assets")
@Validated
@Profile({"local-db", "nacos"})
public class AdminPendingAssetController {

    private final AuctionAssetQueryService queryService;
    private final AuctionReviewService reviewService;

    public AdminPendingAssetController(AuctionAssetQueryService queryService, AuctionReviewService reviewService) {
        this.queryService = queryService;
        this.reviewService = reviewService;
    }

    @PostMapping("/{assetId}/reviews")
    public ApiResponse<AdminReviewResponse> review(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @PathVariable String assetId,
            @RequestHeader(SecurityHeaders.REQUEST_ID)
            @Pattern(
                    regexp = "[A-Za-z0-9_-]{8,48}",
                    message = "must contain 8 to 48 letters, digits, underscores, or hyphens"
            )
            String requestId,
            @Valid @RequestBody AdminReviewRequest request,
            HttpServletRequest servletRequest
    ) {
        AdminReviewResponse response = AdminReviewResponse.from(reviewService.review(
                new AuctionReviewService.ReviewCommand(
                        identity.userId(), parseId(assetId), request.submissionVersion(),
                        request.decision() == AdminReviewRequest.Decision.APPROVE
                                ? AuctionReviewDecision.APPROVED
                                : AuctionReviewDecision.REJECTED,
                        request.comment()
                )
        ));
        return ApiResponse.success(response, traceId(servletRequest));
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

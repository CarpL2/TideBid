package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionBidConflictException;
import io.github.carpl2.tidebid.auction.application.AuctionBidService;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bids")
@Validated
@Profile({"local-db", "nacos"})
public class AuctionBidController {

    private final AuctionBidService bidService;

    public AuctionBidController(AuctionBidService bidService) {
        this.bidService = bidService;
    }

    @PostMapping
    public ApiResponse<AuctionBidResponse.Accepted> place(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @RequestHeader(SecurityHeaders.REQUEST_ID)
            @Pattern(
                    regexp = "[A-Za-z0-9_-]{8,48}",
                    message = "must contain 8 to 48 letters, digits, underscores, or hyphens"
            )
            String requestId,
            @Valid @RequestBody CreateAuctionBidRequest request,
            HttpServletRequest servletRequest
    ) {
        String traceId = traceId(servletRequest);
        return ApiResponse.success(
                AuctionBidResponse.Accepted.from(bidService.place(new AuctionBidService.PlaceBidCommand(
                        identity.userId(), parseId(request.auctionId()), requestId, request.amount()
                ))),
                traceId
        );
    }

    @ExceptionHandler(AuctionBidConflictException.class)
    public ResponseEntity<ApiResponse<AuctionBidResponse.Conflict>> handleBidConflict(
            AuctionBidConflictException exception,
            HttpServletRequest request
    ) {
        AuctionErrorCode errorCode = exception.errorCode();
        ApiResponse<AuctionBidResponse.Conflict> body = new ApiResponse<>(
                errorCode.code(),
                errorCode.defaultMessage(),
                AuctionBidResponse.Conflict.from(exception.snapshot()),
                traceId(request)
        );
        return ResponseEntity.status(errorCode.httpStatus()).body(body);
    }

    private static long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new NumberFormatException("non-positive ID");
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new BusinessException(
                    AuctionErrorCode.AUCTION_INVALID,
                    "auctionId must be a positive integer"
            );
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

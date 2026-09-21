package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionRealtimeSnapshotService;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/realtime/auctions")
@Validated
@Profile({"local-db", "nacos"})
public class AuctionRealtimeSnapshotController {
    private final AuctionRealtimeSnapshotService service;

    public AuctionRealtimeSnapshotController(AuctionRealtimeSnapshotService service) {
        this.service = service;
    }

    @GetMapping("/{auctionId}/snapshot")
    public ApiResponse<AuctionRealtimeSnapshotResponse> snapshot(
            @PathVariable String auctionId,
            @RequestParam(defaultValue = "0") @Min(0) long afterSequenceNo,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit,
            @RequestHeader(value = SecurityHeaders.INTERNAL_USER_ID, required = false) String userId,
            HttpServletRequest request
    ) {
        return ApiResponse.success(
                AuctionRealtimeSnapshotResponse.from(service.find(
                        parseId(auctionId), afterSequenceNo, limit, parseOptionalUserId(userId))),
                traceId(request)
        );
    }

    private static long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) {
            throw new BusinessException(io.github.carpl2.tidebid.auction.domain.AuctionErrorCode.AUCTION_INVALID,
                    "auctionId must be a positive integer");
        }
    }

    private static Long parseOptionalUserId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) {
            throw new BusinessException(io.github.carpl2.tidebid.auction.domain.AuctionErrorCode.AUCTION_INVALID,
                    "internal user id must be a positive integer");
        }
    }

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return value instanceof String trace && TraceIds.isValid(trace) ? trace : TraceIds.create();
    }
}

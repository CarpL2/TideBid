package io.github.carpl2.tidebid.trade.api;

import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import io.github.carpl2.tidebid.trade.application.TradeOrderQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@Profile({"local-db", "nacos"})
public class TradeOrderController {

    private final TradeOrderQueryService queryService;

    public TradeOrderController(TradeOrderQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/mine")
    public ApiResponse<TradeOrderResponse.Page> findMine(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request
    ) {
        return ApiResponse.success(
                TradeOrderResponse.Page.from(queryService.findMine(identity.userId(), page, size)), traceId(request));
    }

    @GetMapping("/sales")
    public ApiResponse<TradeOrderResponse.Page> findSales(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request
    ) {
        return ApiResponse.success(
                TradeOrderResponse.Page.from(queryService.findSales(identity.userId(), page, size)), traceId(request));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<TradeOrderResponse.Detail> findOne(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @PathVariable String orderId,
            HttpServletRequest request
    ) {
        return ApiResponse.success(
                TradeOrderResponse.Detail.from(queryService.findAccessible(
                        identity.userId(), parseId(orderId))), traceId(request));
    }

    static long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "orderId must be a positive integer");
        }
    }

    static String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return traceId instanceof String value && TraceIds.isValid(value) ? value : TraceIds.create();
    }
}

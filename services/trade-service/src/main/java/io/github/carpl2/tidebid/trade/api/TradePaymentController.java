package io.github.carpl2.tidebid.trade.api;

import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import io.github.carpl2.tidebid.trade.application.TradePaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@Validated
@Profile({"local-db", "nacos"})
public class TradePaymentController {

    private final TradePaymentService service;

    public TradePaymentController(TradePaymentService service) {
        this.service = service;
    }

    @PostMapping("/{orderId}/pay")
    public ApiResponse<TradePaymentResponse> pay(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @PathVariable String orderId,
            @RequestHeader(SecurityHeaders.REQUEST_ID)
            @Pattern(regexp = "[A-Za-z0-9_-]{8,48}",
                    message = "must contain 8 to 48 letters, digits, underscores, or hyphens")
            String requestId,
            HttpServletRequest request
    ) {
        String traceId = TradeOrderController.traceId(request);
        return ApiResponse.success(TradePaymentResponse.from(service.pay(
                identity.userId(), TradeOrderController.parseId(orderId), requestId, traceId)), traceId);
    }
}

package io.github.carpl2.tidebid.account.api;

import io.github.carpl2.tidebid.account.application.CreateWalletDebitCommand;
import io.github.carpl2.tidebid.account.application.WalletDebitService;
import io.github.carpl2.tidebid.account.domain.WalletDebit;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/wallet-debits")
@Validated
@Profile({"local-db", "nacos"})
public class InternalWalletDebitController {

    private final WalletDebitService service;

    public InternalWalletDebitController(WalletDebitService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<InternalWalletDebitResponse> debit(
            @RequestHeader(SecurityHeaders.REQUEST_ID)
            @Pattern(
                    regexp = "[A-Za-z0-9_-]{8,48}",
                    message = "must contain 8 to 48 letters, digits, underscores, or hyphens"
            )
            String requestId,
            @Valid @RequestBody InternalWalletDebitRequest request,
            HttpServletRequest servletRequest
    ) {
        WalletDebit debit = service.debit(new CreateWalletDebitCommand(
                request.paymentNo(),
                parseId(request.userId(), "userId"),
                parseId(request.orderId(), "orderId"),
                request.amount()
        ));
        return ApiResponse.success(InternalWalletDebitResponse.from(debit), traceId(servletRequest));
    }

    @GetMapping("/{paymentNo}")
    public ApiResponse<InternalWalletDebitResponse> find(
            @PathVariable
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9:_-]{0,63}")
            String paymentNo,
            HttpServletRequest servletRequest
    ) {
        return ApiResponse.success(
                InternalWalletDebitResponse.from(service.findByPaymentNo(paymentNo)),
                traceId(servletRequest)
        );
    }

    private static long parseId(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_ARGUMENT,
                    field + " must be a positive signed 64-bit integer"
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

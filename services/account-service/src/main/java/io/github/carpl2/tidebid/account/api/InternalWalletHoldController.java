package io.github.carpl2.tidebid.account.api;

import io.github.carpl2.tidebid.account.application.HoldWalletFundsCommand;
import io.github.carpl2.tidebid.account.application.WalletHoldService;
import io.github.carpl2.tidebid.account.domain.WalletHold;
import io.github.carpl2.tidebid.account.domain.WalletHoldBusinessType;
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
@RequestMapping("/internal/wallet-holds")
@Validated
@Profile({"local-db", "nacos"})
public class InternalWalletHoldController {

    private final WalletHoldService walletHoldService;

    public InternalWalletHoldController(WalletHoldService walletHoldService) {
        this.walletHoldService = walletHoldService;
    }

    @PostMapping
    public ApiResponse<InternalWalletHoldResponse> hold(
            @RequestHeader(SecurityHeaders.REQUEST_ID)
            @Pattern(
                    regexp = "[A-Za-z0-9_-]{8,48}",
                    message = "must contain 8 to 48 letters, digits, underscores, or hyphens"
            )
            String requestId,
            @Valid @RequestBody InternalWalletHoldRequest request,
            HttpServletRequest servletRequest
    ) {
        WalletHold hold = walletHoldService.hold(new HoldWalletFundsCommand(
                request.holdNo(),
                parseUserId(request.userId()),
                parseBusinessType(request.businessType()),
                request.amount()
        ));
        return ApiResponse.success(InternalWalletHoldResponse.from(hold), traceId(servletRequest));
    }

    @GetMapping("/{holdNo}")
    public ApiResponse<InternalWalletHoldResponse> find(
            @PathVariable
            @Pattern(regexp = "[A-Za-z0-9:_-]{1,64}")
            String holdNo,
            HttpServletRequest servletRequest
    ) {
        WalletHold hold = walletHoldService.findByHoldNo(holdNo);
        return ApiResponse.success(InternalWalletHoldResponse.from(hold), traceId(servletRequest));
    }

    private static long parseUserId(String userId) {
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException exception) {
            throw invalid("userId must be a positive signed 64-bit integer");
        }
    }

    private static WalletHoldBusinessType parseBusinessType(String businessType) {
        try {
            return WalletHoldBusinessType.valueOf(businessType);
        } catch (IllegalArgumentException exception) {
            throw invalid("businessType is not supported");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.INVALID_ARGUMENT, message);
    }

    private static String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (traceId instanceof String value && TraceIds.isValid(value)) {
            return value;
        }
        return TraceIds.create();
    }
}

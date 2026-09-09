package io.github.carpl2.tidebid.account.api;

import io.github.carpl2.tidebid.account.application.AccountSelfService;
import io.github.carpl2.tidebid.account.application.CurrentAccount;
import io.github.carpl2.tidebid.account.application.WalletBalance;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local-db", "nacos"})
public class CurrentAccountController {

    private final AccountSelfService accountSelfService;

    public CurrentAccountController(AccountSelfService accountSelfService) {
        this.accountSelfService = accountSelfService;
    }

    @GetMapping("/api/users/me")
    public ApiResponse<CurrentAccountResponse> currentAccount(
            @AuthenticationPrincipal AuthenticatedUser identity,
            HttpServletRequest request
    ) {
        CurrentAccount account = accountSelfService.currentAccount(identity);
        return ApiResponse.success(CurrentAccountResponse.from(account), traceId(request));
    }

    @GetMapping("/api/wallets/me")
    public ApiResponse<CurrentWalletResponse> currentWallet(
            @AuthenticationPrincipal AuthenticatedUser identity,
            HttpServletRequest request
    ) {
        WalletBalance wallet = accountSelfService.currentWallet(identity);
        return ApiResponse.success(CurrentWalletResponse.from(wallet), traceId(request));
    }

    private static String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (traceId instanceof String value && TraceIds.isValid(value)) {
            return value;
        }
        return TraceIds.create();
    }
}

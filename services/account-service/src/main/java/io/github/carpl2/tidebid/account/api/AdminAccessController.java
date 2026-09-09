package io.github.carpl2.tidebid.account.api;

import io.github.carpl2.tidebid.account.application.AccountSelfService;
import io.github.carpl2.tidebid.account.application.CurrentAccount;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.security.Role;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local-db", "nacos"})
public class AdminAccessController {

    private final AccountSelfService accountSelfService;

    public AdminAccessController(AccountSelfService accountSelfService) {
        this.accountSelfService = accountSelfService;
    }

    @GetMapping("/api/admin/access-check")
    public ApiResponse<AdminAccessResponse> accessCheck(
            @AuthenticationPrincipal AuthenticatedUser identity,
            HttpServletRequest request
    ) {
        CurrentAccount account = accountSelfService.requireRole(identity, Role.ADMIN);
        return ApiResponse.success(AdminAccessResponse.from(account), traceId(request));
    }

    private static String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (traceId instanceof String value && TraceIds.isValid(value)) {
            return value;
        }
        return TraceIds.create();
    }
}

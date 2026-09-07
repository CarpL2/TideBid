package io.github.carpl2.tidebid.account.api;

import io.github.carpl2.tidebid.account.application.AccountRegistrationService;
import io.github.carpl2.tidebid.account.application.RegisterAccountCommand;
import io.github.carpl2.tidebid.account.application.RegisteredAccount;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Validated
@Profile({"local-db", "nacos"})
public class AccountAuthController {

    private final AccountRegistrationService registrationService;

    public AccountAuthController(AccountRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisteredUserResponse>> register(
            @RequestHeader(SecurityHeaders.REQUEST_ID)
            @Pattern(
                    regexp = "[A-Za-z0-9_-]{8,48}",
                    message = "must contain 8 to 48 letters, digits, underscores, or hyphens"
            )
            String requestId,
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest
    ) {
        RegisteredAccount account = registrationService.register(new RegisterAccountCommand(
                requestId,
                request.username(),
                request.password(),
                request.nickname()
        ));
        ApiResponse<RegisteredUserResponse> response = ApiResponse.success(
                RegisteredUserResponse.from(account),
                traceId(servletRequest)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private static String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (traceId instanceof String value && TraceIds.isValid(value)) {
            return value;
        }
        return TraceIds.create();
    }
}

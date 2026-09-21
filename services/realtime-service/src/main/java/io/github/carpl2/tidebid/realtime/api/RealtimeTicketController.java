package io.github.carpl2.tidebid.realtime.api;

import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketApplicationService;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketIssue;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/realtime/tickets")
@ConditionalOnProperty(prefix = "tidebid.realtime.redis", name = "enabled", havingValue = "true")
public class RealtimeTicketController {

    private final RealtimeTicketApplicationService service;

    public RealtimeTicketController(RealtimeTicketApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TicketResponse>> issue(
            @AuthenticationPrincipal AuthenticatedUser identity,
            HttpServletRequest request
    ) {
        RealtimeTicketIssue issue = service.issue(identity, sourceIp(request));
        String traceId = traceId(request);
        return ResponseEntity.ok(ApiResponse.success(new TicketResponse(issue.ticket(), issue.expiresAt()), traceId));
    }

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return value instanceof String traceId && TraceIds.isValid(traceId)
                ? traceId : TraceIds.resolve(request.getHeader(SecurityHeaders.TRACE_ID));
    }

    private static String sourceIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String firstHop = forwardedFor.split(",", 2)[0].trim();
            if (firstHop.matches("[0-9A-Fa-f:.]{2,45}")) {
                return firstHop;
            }
        }
        return request.getRemoteAddr();
    }

    public record TicketResponse(String ticket, Instant expiresAt) {
        @Override
        public String toString() {
            return "TicketResponse[ticket=[REDACTED], expiresAt=" + expiresAt + "]";
        }
    }
}

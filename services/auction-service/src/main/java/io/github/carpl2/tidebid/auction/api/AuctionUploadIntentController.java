package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionUploadIntentService;
import io.github.carpl2.tidebid.auction.application.AuctionUploadIntentService.UploadIntentCommand;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assets/upload-intents")
@Validated
@Profile({"local-db", "nacos"})
public class AuctionUploadIntentController {

    private final AuctionUploadIntentService uploadIntentService;

    public AuctionUploadIntentController(AuctionUploadIntentService uploadIntentService) {
        this.uploadIntentService = uploadIntentService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UploadIntentResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @RequestHeader(SecurityHeaders.REQUEST_ID)
            @Pattern(
                    regexp = "[A-Za-z0-9_-]{8,48}",
                    message = "must contain 8 to 48 letters, digits, underscores, or hyphens"
            )
            String requestId,
            @RequestBody CreateUploadIntentRequest request,
            HttpServletRequest servletRequest
    ) {
        AuctionUploadIntentService.UploadIntent intent = uploadIntentService.create(new UploadIntentCommand(
                identity.userId(),
                request.originalFilename(),
                request.contentType(),
                request.contentLength(),
                request.checksumSha256()
        ));
        ApiResponse<UploadIntentResponse> response = ApiResponse.success(
                UploadIntentResponse.from(intent),
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

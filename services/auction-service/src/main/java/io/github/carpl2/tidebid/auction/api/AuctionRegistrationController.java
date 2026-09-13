package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionRegistrationQueryService;
import io.github.carpl2.tidebid.auction.application.AuctionRegistrationService;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/registrations")
@Validated
@Profile({"local-db", "nacos"})
public class AuctionRegistrationController {

    private final AuctionRegistrationService registrationService;
    private final AuctionRegistrationQueryService queryService;

    public AuctionRegistrationController(
            AuctionRegistrationService registrationService,
            AuctionRegistrationQueryService queryService
    ) {
        this.registrationService = registrationService;
        this.queryService = queryService;
    }

    @PostMapping
    public ApiResponse<AuctionRegistrationResponse.Detail> register(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @RequestHeader(SecurityHeaders.REQUEST_ID)
            @Pattern(regexp = "[A-Za-z0-9_-]{8,48}", message = "must contain 8 to 48 letters, digits, underscores, or hyphens")
            String requestId,
            @Valid @RequestBody CreateAuctionRegistrationRequest request,
            HttpServletRequest servletRequest
    ) {
        String traceId = traceId(servletRequest);
        return ApiResponse.success(
                AuctionRegistrationResponse.Detail.from(registrationService.register(
                        new AuctionRegistrationService.RegisterCommand(
                                identity.userId(), parseId(request.auctionId(), "auctionId"), requestId, traceId
                        )
                )),
                traceId
        );
    }

    @GetMapping("/mine")
    public ApiResponse<AuctionRegistrationResponse.Page> findMine(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request
    ) {
        return ApiResponse.success(
                AuctionRegistrationResponse.Page.from(queryService.findMine(identity.userId(), page, size)),
                traceId(request)
        );
    }

    @GetMapping("/{registrationId}")
    public ApiResponse<AuctionRegistrationResponse.Detail> findMine(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @PathVariable String registrationId,
            HttpServletRequest request
    ) {
        return ApiResponse.success(
                AuctionRegistrationResponse.Detail.from(
                        queryService.findMine(identity.userId(), parseId(registrationId, "registrationId"))
                ),
                traceId(request)
        );
    }

    private static long parseId(String value, String name) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new NumberFormatException("non-positive ID");
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new BusinessException(AuctionErrorCode.AUCTION_INVALID, name + " must be a positive integer");
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

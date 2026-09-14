package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionDraftCreationService;
import io.github.carpl2.tidebid.auction.application.AuctionDraftUpdateService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assets")
@Validated
@Profile({"local-db", "nacos"})
public class AuctionDraftController {

    private final AuctionDraftCreationService creationService;
    private final AuctionDraftUpdateService updateService;

    public AuctionDraftController(
            AuctionDraftCreationService creationService,
            AuctionDraftUpdateService updateService
    ) {
        this.creationService = creationService;
        this.updateService = updateService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AuctionDraftResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @RequestHeader(SecurityHeaders.REQUEST_ID)
            @Pattern(
                    regexp = "[A-Za-z0-9_-]{8,48}",
                    message = "must contain 8 to 48 letters, digits, underscores, or hyphens"
            )
            String requestId,
            @Valid @RequestBody CreateAuctionDraftRequest request,
            HttpServletRequest servletRequest
    ) {
        AuctionDraftResponse response = AuctionDraftResponse.from(creationService.create(
                new AuctionDraftCreationService.CreateDraftCommand(
                        identity.userId(), request.title(), request.description(), request.category(),
                        request.itemCondition(), request.startPrice(), request.bidIncrement(),
                        request.depositAmount(), request.startAt(), request.endAt(), request.imageObjectKeys()
                )
        ));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, traceId(servletRequest)));
    }

    @PutMapping("/{assetId}")
    public ApiResponse<AuctionDraftResponse> update(
            @AuthenticationPrincipal AuthenticatedUser identity,
            @PathVariable String assetId,
            @RequestHeader(SecurityHeaders.REQUEST_ID)
            @Pattern(
                    regexp = "[A-Za-z0-9_-]{8,48}",
                    message = "must contain 8 to 48 letters, digits, underscores, or hyphens"
            )
            String requestId,
            @Valid @RequestBody UpdateAuctionDraftRequest request,
            HttpServletRequest servletRequest
    ) {
        AuctionDraftResponse response = AuctionDraftResponse.from(updateService.update(
                new AuctionDraftUpdateService.UpdateDraftCommand(
                        identity.userId(), parseId(assetId), request.itemVersion(), request.sessionVersion(),
                        request.title(), request.description(), request.category(), request.itemCondition(),
                        request.startPrice(), request.bidIncrement(), request.depositAmount(),
                        request.startAt(), request.endAt()
                )
        ));
        return ApiResponse.success(response, traceId(servletRequest));
    }

    private static long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new NumberFormatException("non-positive ID");
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "assetId must be a positive integer");
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

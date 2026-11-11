package com.cinema.cms.controller;
import com.cinema.cms.dto.*;
import com.cinema.cms.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/v1/manager-overrides",produces="application/json")
@PreAuthorize("hasRole('STAFF')")
@SecurityRequirement(name="bearerAuth")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400", description="VALIDATION_ERROR", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="AUTH_TOKEN_INVALID / AUTH_TOKEN_EXPIRED", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="ACCESS_DENIED / manager approval errors", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404", description="BOOKING_NOT_FOUND", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409", description="RESOURCE_CONFLICT / SEAT_UPDATE_CONFLICT / REFUND_NOT_ELIGIBLE", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="422", description="REFUND_NOT_ELIGIBLE / REFUND_DEADLINE_PASSED / TICKET_NOT_REFUNDABLE / TICKET_ALREADY_CANCELLED", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ApiErrorResponse.class)))
})
public class ManagerOverrideController {
    private final RefundService refunds;
    public ManagerOverrideController(RefundService refunds) { this.refunds=refunds; }
    @PostMapping(consumes="application/json")
    @Operation(summary="Approve a specific refund using Manager username and PIN at the POS",description="STAFF stays logged in. ACTIVE MANAGER credentials and cashConfirmed=true required. Approval lasts 180 seconds, binds ticket selection/reason/amount/session, and cannot grant a Manager login.")
    public ApiResponse<ManagerOverrideResponse> approve(@Valid @RequestBody ManagerOverrideRequest request,@AuthenticationPrincipal SessionPrincipal actor) {
        return new ApiResponse<>(refunds.approve(request,actor));
    }
}

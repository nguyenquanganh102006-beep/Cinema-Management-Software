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
@RequestMapping(value="/api/v1/refunds",produces="application/json")
@SecurityRequirement(name="bearerAuth")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400", description="VALIDATION_ERROR", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="AUTH_TOKEN_INVALID / AUTH_TOKEN_EXPIRED", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="ACCESS_DENIED / manager approval errors", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404", description="BOOKING_NOT_FOUND", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409", description="RESOURCE_CONFLICT / SEAT_UPDATE_CONFLICT / REFUND_NOT_ELIGIBLE", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="422", description="REFUND_NOT_ELIGIBLE / REFUND_DEADLINE_PASSED / TICKET_NOT_REFUNDABLE / TICKET_ALREADY_CANCELLED", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ApiErrorResponse.class)))
})
public class RefundController {
    private final RefundService refunds;
    public RefundController(RefundService refunds) { this.refunds=refunds; }
    @GetMapping("/eligibility")
    @PreAuthorize("hasAnyRole('STAFF','MANAGER')")
    @Operation(summary="Look up refund eligibility for POS or ONLINE tickets",description="Exactly one bookingCode or ticketCode. Eligibility is rechecked when committing; VALID and at least 30 minutes before showtime.")
    public ApiResponse<RefundEligibilityResponse> eligibility(@RequestParam(required=false) String bookingCode,
        @RequestParam(required=false) String ticketCode,@AuthenticationPrincipal SessionPrincipal actor) {
        return new ApiResponse<>(refunds.eligibility(bookingCode,ticketCode,actor));
    }
    @PostMapping(consumes="application/json")
    @PreAuthorize("hasRole('STAFF')")
    @Operation(summary="Refund selected tickets in cash with manager approval",description="STAFF session plus a matching single-use override. No F&B refund. Repeating the same token and intent returns the existing refund without paying twice.")
    public ApiResponse<RefundResponse> refund(@Valid @RequestBody RefundRequest request,@AuthenticationPrincipal SessionPrincipal actor) {
        return new ApiResponse<>(refunds.refund(request,actor));
    }
}

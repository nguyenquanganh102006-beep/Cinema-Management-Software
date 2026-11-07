package com.cinema.cms.controller;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.PosPaymentService;
import com.cinema.cms.service.SessionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** POS-only payment endpoints. Kept separate so CUSTOMER cannot share a controller role. */
@RestController
@RequestMapping(value = "/api/v1/bookings/{bookingId}/payments", produces = "application/json")
@PreAuthorize("hasRole('STAFF')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "POS Payments", description = "Thanh toán tại quầy cho Booking kênh POS (STAFF)")
public class PosPaymentController {
    private final PosPaymentService payments;

    public PosPaymentController(PosPaymentService payments) {
        this.payments = payments;
    }

    @PostMapping(value = "/cash", consumes = "application/json")
    @Operation(summary = "POS: collect cash, record the age check and issue tickets")
    public ApiResponse<CashPaymentResponse> cash(
            @PathVariable Long bookingId,
            @Valid @RequestBody CashPaymentRequest request,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(payments.payCash(bookingId, request, principal));
    }

    @PostMapping(value = "/vietqr", consumes = "application/json")
    @Operation(summary = "POS: create a PENDING VietQR transfer for the booking")
    public ApiResponse<VietQrPaymentResponse> vietQr(
            @PathVariable Long bookingId,
            @Valid @RequestBody VietQrPaymentRequest request,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(payments.createVietQr(bookingId, request, principal));
    }

    @PostMapping("/vietqr/confirm")
    @Operation(summary = "POS: confirm the transfer was received and issue tickets")
    public ApiResponse<VietQrConfirmResponse> confirmVietQr(
            @PathVariable Long bookingId,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(payments.confirmVietQr(bookingId, principal));
    }
}

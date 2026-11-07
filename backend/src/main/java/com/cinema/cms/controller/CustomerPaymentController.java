package com.cinema.cms.controller;

import com.cinema.cms.dto.ApiResponse;
import com.cinema.cms.dto.VnPayPaymentResponse;
import com.cinema.cms.service.SessionPrincipal;
import com.cinema.cms.service.VnPayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Customer-only online payment endpoints. Kept separate from the STAFF POS controller. */
@RestController
@RequestMapping(value = "/api/v1/bookings/{bookingId}/payments", produces = "application/json")
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer Payments", description = "Thanh toán online VNPay cho Booking ONLINE")
public class CustomerPaymentController {
    private final VnPayService vnPay;

    public CustomerPaymentController(VnPayService vnPay) {
        this.vnPay = vnPay;
    }

    @PostMapping("/vnpay")
    @Operation(summary = "Create or refresh the VNPay redirect URL for the caller's ONLINE booking")
    public ApiResponse<VnPayPaymentResponse> vnPay(
            @PathVariable Long bookingId,
            @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest request) {
        return new ApiResponse<>(
                vnPay.createPayment(bookingId, principal, request.getRemoteAddr()));
    }
}

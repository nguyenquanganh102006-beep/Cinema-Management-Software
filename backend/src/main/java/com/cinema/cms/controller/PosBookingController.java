package com.cinema.cms.controller;

import com.cinema.cms.dto.ApiResponse;
import com.cinema.cms.dto.PageResponse;
import com.cinema.cms.dto.PosBookingDetailResponse;
import com.cinema.cms.dto.PosBookingSummaryResponse;
import com.cinema.cms.service.PosBookingService;
import com.cinema.cms.service.SessionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** STAFF-only counter history. ONLINE bookings are never exposed here. */
@RestController
@RequestMapping(value = "/api/v1/pos/bookings", produces = "application/json")
@PreAuthorize("hasRole('STAFF')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "POS History", description = "Lịch sử bán vé tại quầy (STAFF)")
public class PosBookingController {
    private final PosBookingService bookings;

    public PosBookingController(PosBookingService bookings) {
        this.bookings = bookings;
    }

    @GetMapping
    @Operation(summary = "List PAID POS bookings with optional code/date/payment filters")
    public ApiResponse<PageResponse<PosBookingSummaryResponse>> list(
            @RequestParam(required = false) String bookingCode,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(
                bookings.list(bookingCode, date, paymentMethod, page, size, principal));
    }

    @GetMapping("/{bookingId}")
    @Operation(summary = "Read a POS booking in detail; ONLINE bookings return 404")
    public ApiResponse<PosBookingDetailResponse> detail(
            @PathVariable Long bookingId,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(bookings.detail(bookingId, principal));
    }
}

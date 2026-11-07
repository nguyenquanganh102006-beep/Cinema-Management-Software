package com.cinema.cms.controller;

import com.cinema.cms.dto.ApiResponse;
import com.cinema.cms.dto.MeBookingDetailResponse;
import com.cinema.cms.dto.MeBookingSummaryResponse;
import com.cinema.cms.dto.PageResponse;
import com.cinema.cms.service.CustomerBookingQueryService;
import com.cinema.cms.service.SessionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** CUSTOMER-only history scoped to the JWT user id. */
@RestController
@RequestMapping(value = "/api/v1/me/bookings", produces = "application/json")
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "My Bookings", description = "Lịch sử Booking của CUSTOMER đang đăng nhập")
public class CustomerBookingController {
    private final CustomerBookingQueryService bookings;

    public CustomerBookingController(CustomerBookingQueryService bookings) {
        this.bookings = bookings;
    }

    @GetMapping
    @Operation(summary = "List the authenticated customer's bookings, newest first")
    public ApiResponse<PageResponse<MeBookingSummaryResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(bookings.list(page, size, principal));
    }

    @GetMapping("/{bookingId}")
    @Operation(summary = "Read one of the authenticated customer's bookings")
    public ApiResponse<MeBookingDetailResponse> detail(
            @PathVariable Long bookingId,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(bookings.detail(bookingId, principal));
    }
}

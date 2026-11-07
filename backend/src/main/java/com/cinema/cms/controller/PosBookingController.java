package com.cinema.cms.controller;

import com.cinema.cms.dto.ApiErrorResponse;
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
@io.swagger.v3.oas.annotations.responses.ApiResponses({
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description =
          "VALIDATION_ERROR (bad date, paymentMethod other than CASH/VIETQR, or paging outside"
              + " 0..n / 1..100)"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "401",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "AUTH_TOKEN_INVALID / AUTH_TOKEN_EXPIRED"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "403",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "ACCESS_DENIED (only STAFF may read POS history)"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "BOOKING_NOT_FOUND (also for an ONLINE booking or a missing booking)")
})
public class PosBookingController {
    private final PosBookingService bookings;

    public PosBookingController(PosBookingService bookings) {
        this.bookings = bookings;
    }

    @GetMapping
    @Operation(
            summary = "List PAID POS bookings with optional code/date/payment filters",
            description =
                    "Role: STAFF. Only channel=POS and status=PAID bookings are visible; ONLINE"
                            + " bookings are never returned. date is yyyy-MM-dd interpreted in"
                            + " Asia/Ho_Chi_Minh, paymentMethod is CASH or VIETQR, size is 1..100"
                            + " (default 20), and sort is paidAt DESC, bookingId DESC.")
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
    @Operation(
            summary = "Read a POS booking in detail; ONLINE bookings return 404",
            description =
                    "Role: STAFF. Returns showtime/movie, seats, tickets, F&B lines with price"
                            + " snapshots, totals, staff and paidAt. It never returns PIN, JWT,"
                            + " hold token or payment secrets; refundStatus is reserved for Sprint F"
                            + " and is currently null.")
    public ApiResponse<PosBookingDetailResponse> detail(
            @PathVariable Long bookingId,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(bookings.detail(bookingId, principal));
    }
}

package com.cinema.cms.controller;

import com.cinema.cms.dto.ApiErrorResponse;
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
@io.swagger.v3.oas.annotations.responses.ApiResponses({
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "VALIDATION_ERROR (page < 0 or size outside 1..100)"),
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
      description = "ACCESS_DENIED (STAFF cannot use /me/bookings)"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "BOOKING_NOT_FOUND (also when the booking belongs to another customer)")
})
public class CustomerBookingController {
    private final CustomerBookingQueryService bookings;

    public CustomerBookingController(CustomerBookingQueryService bookings) {
        this.bookings = bookings;
    }

    @GetMapping
    @Operation(
            summary = "List the authenticated customer's bookings, newest first",
            description =
                    "Role: CUSTOMER. userId always comes from the JWT, never from a query"
                            + " parameter. page starts at 0, size is 1..100 (default 20); sort is"
                            + " createdAt DESC, bookingId DESC.")
    public ApiResponse<PageResponse<MeBookingSummaryResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(bookings.list(page, size, principal));
    }

    @GetMapping("/{bookingId}")
    @Operation(
            summary = "Read one of the authenticated customer's bookings",
            description =
                    "Role: CUSTOMER (owner only). Another customer's booking returns 404"
                            + " BOOKING_NOT_FOUND so its existence is not disclosed.")
    public ApiResponse<MeBookingDetailResponse> detail(
            @PathVariable Long bookingId,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(bookings.detail(bookingId, principal));
    }
}

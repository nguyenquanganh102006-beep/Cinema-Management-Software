package com.cinema.cms.controller;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.BookingService;
import com.cinema.cms.service.SessionPrincipal;
import com.cinema.cms.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/bookings", produces = "application/json")
@PreAuthorize("hasAnyRole('STAFF','CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "VALIDATION_ERROR / INVALID_SEAT"),
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
      description = "ACCESS_DENIED"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description =
          "BOOKING_NOT_FOUND / SHOWTIME_NOT_FOUND / MOVIE_NOT_FOUND /"
              + " CONCESSION_ITEM_NOT_FOUND"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "409",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description =
          "BOOKING_ALREADY_EXISTS / SEAT_HOLD_EXPIRED / SEAT_HOLD_MISMATCH /"
              + " SEAT_UPDATE_CONFLICT"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "422",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description =
          "AGE_CONFIRMATION_REQUIRED / CONCESSION_ITEM_INACTIVE / PRICING_RULE_MISSING")
})
public class BookingController {
    private final BookingService bookings;
    private final TicketService tickets;

    public BookingController(BookingService bookings, TicketService tickets) {
        this.bookings = bookings;
        this.tickets = tickets;
    }

    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "PENDING Booking created; holds stay HOLDING until payment")
    @Operation(
            summary =
                    "Create a PENDING booking from an owned hold; prices are computed by the backend",
            description =
                    "Role: STAFF (channel POS) or CUSTOMER (channel ONLINE). The caller must own"
                            + " the hold token. userId, role and prices always come from the"
                            + " authenticated session and backend pricing rules.")
    public ApiResponse<BookingResponse> create(
            @Valid @RequestBody CreateBookingRequest request,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(bookings.create(request, principal));
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Read a booking owned by the authenticated customer or staff member",
            description =
                    "Role: STAFF or CUSTOMER. A booking owned by another user returns 404 so its"
                            + " existence is not disclosed.")
    public ApiResponse<BookingResponse> get(
            @PathVariable Long id,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(bookings.get(id, principal));
    }

    @GetMapping("/{id}/tickets")
    @Operation(
            summary = "List tickets of a booking owned by the customer or visible to staff",
            description =
                    "Role: STAFF or CUSTOMER (owner only). Tickets exist only after the payment is"
                            + " PAID; a booking owned by another user returns 404.")
    public ApiResponse<List<TicketResponse>> tickets(
            @PathVariable Long id,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(tickets.listForBooking(id, principal));
    }
}

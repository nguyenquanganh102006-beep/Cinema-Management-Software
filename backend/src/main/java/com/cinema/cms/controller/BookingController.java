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
public class BookingController {
    private final BookingService bookings;
    private final TicketService tickets;

    public BookingController(BookingService bookings, TicketService tickets) {
        this.bookings = bookings;
        this.tickets = tickets;
    }

    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary =
                    "Create a PENDING booking from an owned hold; prices are computed by the backend")
    public ApiResponse<BookingResponse> create(
            @Valid @RequestBody CreateBookingRequest request,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(bookings.create(request, principal));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read a booking owned by the authenticated customer or staff member")
    public ApiResponse<BookingResponse> get(
            @PathVariable Long id,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(bookings.get(id, principal));
    }

    @GetMapping("/{id}/tickets")
    @Operation(summary = "List tickets of a booking owned by the customer or visible to staff")
    public ApiResponse<List<TicketResponse>> tickets(
            @PathVariable Long id,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(tickets.listForBooking(id, principal));
    }
}

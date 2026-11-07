package com.cinema.cms.controller;

import com.cinema.cms.dto.ApiErrorResponse;
import com.cinema.cms.dto.ApiResponse;
import com.cinema.cms.dto.TicketResponse;
import com.cinema.cms.service.SessionPrincipal;
import com.cinema.cms.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/tickets", produces = "application/json")
@PreAuthorize("hasAnyRole('STAFF','CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Tickets", description = "Đọc vé điện tử (STAFF hoặc CUSTOMER sở hữu Booking)")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
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
          "TICKET_NOT_FOUND / BOOKING_NOT_FOUND (a ticket of another customer also returns"
              + " TICKET_NOT_FOUND)")
})
public class TicketController {
    private final TicketService tickets;

    public TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    @GetMapping("/{ticketCode}")
    @Operation(
            summary = "Read a ticket by code; CUSTOMER must own the booking",
            description =
                    "Role: STAFF or CUSTOMER (owner only). Never returns the hold token, JWT, PIN"
                            + " or payment secrets.")
    public ApiResponse<TicketResponse> get(
            @PathVariable String ticketCode,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(tickets.getByCode(ticketCode, principal));
    }
}

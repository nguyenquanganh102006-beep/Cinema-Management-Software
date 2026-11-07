package com.cinema.cms.controller;

import com.cinema.cms.dto.ApiErrorResponse;
import com.cinema.cms.dto.ApiResponse;
import com.cinema.cms.dto.TicketValidationRequest;
import com.cinema.cms.dto.TicketValidationResponse;
import com.cinema.cms.service.SessionPrincipal;
import com.cinema.cms.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** STAFF-only gate scan. Kept separate from the shared ticket read controller. */
@RestController
@RequestMapping(value = "/api/v1/tickets", produces = "application/json")
@PreAuthorize("hasRole('STAFF')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Ticket Gate", description = "Soát vé tại cổng (STAFF)")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "VALIDATION_ERROR"),
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
      description = "ACCESS_DENIED (only STAFF may scan at the gate)"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "TICKET_NOT_FOUND / BOOKING_NOT_FOUND"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "409",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "TICKET_ALREADY_USED / SEAT_UPDATE_CONFLICT"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "422",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description =
          "TICKET_NOT_VALID / TICKET_OUTSIDE_SHOWTIME / AGE_VERIFICATION_REQUIRED")
})
public class TicketValidationController {
    private final TicketService tickets;

    public TicketValidationController(TicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping(value = "/validate", consumes = "application/json")
    @Operation(
            summary =
                    "Mark a VALID ticket USED; enforces the gate window and the age check for T13/T16/T18",
            description =
                    "Role: STAFF. The gate opens 60 minutes before showtime start and closes 30"
                            + " minutes after showtime end (end already includes the 15-minute"
                            + " cleaning buffer). A restrictive movie (T13/T16/T18) requires"
                            + " ageDocumentChecked=true; the check is written to age_verifications"
                            + " with stage GATE. Concurrent scans serialize on the ticket row, so"
                            + " exactly one succeeds.")
    public ApiResponse<TicketValidationResponse> validate(
            @Valid @RequestBody TicketValidationRequest request,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(tickets.validate(request, principal));
    }
}

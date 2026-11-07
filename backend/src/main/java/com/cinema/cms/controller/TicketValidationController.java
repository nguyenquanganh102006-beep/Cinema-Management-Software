package com.cinema.cms.controller;

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
public class TicketValidationController {
    private final TicketService tickets;

    public TicketValidationController(TicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping(value = "/validate", consumes = "application/json")
    @Operation(
            summary =
                    "Mark a VALID ticket USED; enforces the gate window and the age check for T13/T16/T18")
    public ApiResponse<TicketValidationResponse> validate(
            @Valid @RequestBody TicketValidationRequest request,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(tickets.validate(request, principal));
    }
}

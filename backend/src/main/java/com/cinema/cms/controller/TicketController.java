package com.cinema.cms.controller;

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
public class TicketController {
    private final TicketService tickets;

    public TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    @GetMapping("/{ticketCode}")
    @Operation(summary = "Read a ticket by code; CUSTOMER must own the booking")
    public ApiResponse<TicketResponse> get(
            @PathVariable String ticketCode,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(tickets.getByCode(ticketCode, principal));
    }
}

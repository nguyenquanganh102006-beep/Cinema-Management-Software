package com.cinema.cms.controller;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.PricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/pricing", produces = "application/json")
@PreAuthorize("hasAnyRole('STAFF','CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
public class PricingQuoteController {
    private final PricingService pricing;

    public PricingQuoteController(PricingService pricing) {
        this.pricing = pricing;
    }

    @PostMapping(value = "/quote", consumes = "application/json")
    @Operation(summary = "Quote ticket prices per seat; display only, no seats are held")
    public ApiResponse<PricingQuoteResponse> quote(
            @Valid @RequestBody PricingQuoteRequest request) {
        return new ApiResponse<>(pricing.quote(request.showtimeId(), request.seatIds()));
    }
}

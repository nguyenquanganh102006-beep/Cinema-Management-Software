package com.cinema.cms.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record PricingQuoteRequest(
        @NotNull @Positive Long showtimeId,
        @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> seatIds) {}

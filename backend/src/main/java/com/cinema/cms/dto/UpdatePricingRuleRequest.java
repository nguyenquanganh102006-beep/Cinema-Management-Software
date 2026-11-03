package com.cinema.cms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record UpdatePricingRuleRequest(
        @Schema(example = "80000") @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal amount,
        @Size(max = 255) String description) {
    public UpdatePricingRuleRequest {
        description = description == null || description.isBlank() ? null : description.strip();
    }
}
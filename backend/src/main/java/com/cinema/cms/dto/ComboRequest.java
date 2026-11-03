package com.cinema.cms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public record ComboRequest(
        @Schema(example = "Combo đôi") @NotBlank @Size(max = 100) String name,
        @Schema(example = "120000") @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal price,
        @NotEmpty @Size(max = 50) List<@Valid Component> components) {

    public ComboRequest { name = name == null ? null : name.strip(); }

    public record Component(@Schema(example = "1") @NotNull @Positive Integer itemId, @Schema(example = "2") @NotNull @Positive Integer quantity) {}
}
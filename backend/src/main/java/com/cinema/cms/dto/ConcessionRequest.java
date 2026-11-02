package com.cinema.cms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ConcessionRequest(
        @Schema(example = "Bắp rang lớn") @NotBlank @Size(max = 100) String name,
        @Schema(description = "Đồng, không âm, tối đa 8 chữ số nguyên và 2 chữ số thập phân", example = "55000") @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal price) {
    public ConcessionRequest { name = name == null ? null : name.strip(); }
}
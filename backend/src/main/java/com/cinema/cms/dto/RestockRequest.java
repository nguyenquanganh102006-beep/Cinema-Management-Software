package com.cinema.cms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record RestockRequest(
        @Schema(description = "Số nguyên dương", example = "50") @NotNull @Digits(integer = 9, fraction = 0) BigDecimal quantity,
        @Schema(example = "Nhập hàng từ nhà cung cấp A") @Size(max = 500) String note) {
    public RestockRequest { note = note == null || note.isBlank() ? null : note.strip(); }
}
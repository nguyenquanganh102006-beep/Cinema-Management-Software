package com.cinema.cms.dto;

import com.cinema.cms.entity.SeatType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.Locale;

public record SeatRequest(
        @Schema(description = "Hàng ghế, 1 đến 5 chữ cái, tự chuyển thành chữ hoa", example = "A") @NotBlank @Pattern(regexp = "[a-zA-Z]{1,5}") String row,
        @Schema(example = "7") @NotNull @Positive Integer number,
        @Schema(description = "Mặc định STANDARD nếu bỏ trống", example = "VIP") SeatType type) {
    public SeatRequest {
        row = row == null ? null : row.strip().toUpperCase(Locale.ROOT);
        if (type == null) type = SeatType.STANDARD;
    }
}
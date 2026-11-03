package com.cinema.cms.dto;

import com.cinema.cms.entity.AuditoriumType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

public record AuditoriumRequest(
        @Schema(example = "Phòng 1") @NotBlank @Size(max = 50) String name,
        @Schema(description = "Sức chứa khai báo. Số ghế thực tế không được vượt quá giá trị này", example = "120") @NotNull @Positive Integer totalSeats,
        @Schema(description = "STANDARD_2D, 3D hoặc IMAX", example = "3D", allowableValues = {"STANDARD_2D", "3D", "IMAX"}) @NotNull AuditoriumType type) {
    public AuditoriumRequest { name = name == null ? null : name.strip(); }
}
package com.cinema.cms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SweetboxPairRequest(
        @Schema(example = "10") @NotNull @Positive Long seatId,
        @Schema(example = "11") @NotNull @Positive Long pairSeatId) {}
package com.cinema.cms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record StaffStatusRequest(@Schema(description = "true = ACTIVE, false = INACTIVE", example = "false") @NotNull Boolean active) {}
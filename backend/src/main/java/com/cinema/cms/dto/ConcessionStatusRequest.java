package com.cinema.cms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record ConcessionStatusRequest(@Schema(example = "false") @NotNull Boolean active) {}
package com.cinema.cms.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;

public record CreateShowtimeRequest(
        @NotNull @Positive @JsonDeserialize(using = ShowtimeRequestValues.MovieId.class) Long movieId,
        @NotNull @Positive @JsonDeserialize(using = ShowtimeRequestValues.AuditoriumId.class) Integer auditoriumId,
        @NotNull @JsonDeserialize(using = ShowtimeRequestValues.StartTime.class)
        @Schema(type = "string", example = "2026-11-14T19:30:00+07:00") OffsetDateTime startTime) {}

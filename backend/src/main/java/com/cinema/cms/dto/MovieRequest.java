package com.cinema.cms.dto;

import com.cinema.cms.entity.AgeRating;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record MovieRequest(
        @Schema(example = "Mưa Đỏ") @NotBlank @Size(max = 200) String title,
        @Schema(example = "124") @NotNull @Positive Integer durationMinutes,
        @Schema(example = "T13") @NotNull AgeRating ageRating,
        @Schema(example = "2026-11-14") @NotNull LocalDate releaseDate,
        @Size(max = 5000) String description,
        @Size(max = 150) String genre,
        @Size(max = 100) String country,
        @Size(max = 100) String language,
        @Size(max = 150) String director,
        @Schema(example = "https://cdn.example.test/posters/mua-do.jpg")
        @Size(max = 2048) @Pattern(regexp = "^https?://\\S+$", message = "Phải là địa chỉ http hoặc https") String poster,
        @Schema(example = "https://www.youtube.com/watch?v=abc123")
        @Size(max = 2048) @Pattern(regexp = "^https?://\\S+$", message = "Phải là địa chỉ http hoặc https") String trailer) {

    public MovieRequest {
        title = strip(title);
        description = blankToNull(description);
        genre = blankToNull(genre);
        country = blankToNull(country);
        language = blankToNull(language);
        director = blankToNull(director);
        poster = blankToNull(poster);
        trailer = blankToNull(trailer);
    }

    private static String strip(String value) { return value == null ? null : value.strip(); }
    private static String blankToNull(String value) {
        String stripped = strip(value);
        return stripped == null || stripped.isEmpty() ? null : stripped;
    }
}
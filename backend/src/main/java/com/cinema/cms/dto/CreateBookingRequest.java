package com.cinema.cms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record CreateBookingRequest(
        @NotNull @Positive Long showtimeId,
        @NotBlank
                @Pattern(
                        regexp =
                                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                        message = "Token giữ ghế phải là UUID")
                String holdToken,
        @Valid @Size(max = 100) List<@NotNull ConcessionItem> concessions,
        Boolean ageConfirmed) {

    public record ConcessionItem(
            @NotNull @Positive Integer itemId, @NotNull @Positive Integer quantity) {}
}

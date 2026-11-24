package com.cinema.cms.dto;

import jakarta.validation.constraints.*;

public record TicketValidationRequest(
        @NotBlank(message = "Mã vé là bắt buộc") String ticketCode,
        @NotNull @Positive Long showtimeId,
        Boolean ageDocumentChecked) {}

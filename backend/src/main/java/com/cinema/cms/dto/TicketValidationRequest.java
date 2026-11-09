package com.cinema.cms.dto;

import jakarta.validation.constraints.NotBlank;

public record TicketValidationRequest(
        @NotBlank(message = "Mã vé là bắt buộc") String ticketCode,
        Boolean ageDocumentChecked) {}

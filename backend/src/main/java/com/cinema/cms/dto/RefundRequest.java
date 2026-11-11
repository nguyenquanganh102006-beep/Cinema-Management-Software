package com.cinema.cms.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record RefundRequest(
    @NotNull @Positive Long bookingId,
    @NotEmpty @Size(max=200) List<@NotNull @Positive Long> ticketIds,
    @NotBlank @Size(max=255) String reason,
    @NotBlank @Pattern(regexp="[a-f0-9]{64}") String overrideToken,
    @NotNull @AssertTrue Boolean cashConfirmed) {
    @Override public String toString() { return "RefundRequest[REDACTED]"; }
}

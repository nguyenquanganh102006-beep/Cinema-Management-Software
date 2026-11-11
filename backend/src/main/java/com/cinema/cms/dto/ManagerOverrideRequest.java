package com.cinema.cms.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record ManagerOverrideRequest(
    @NotBlank @Size(max=50) String username,
    @NotBlank @Pattern(regexp="[0-9]{6,12}") String pin,
    @NotBlank @Pattern(regexp="REFUND_APPROVAL") String scope,
    @NotNull @Positive Long bookingId,
    @NotEmpty @Size(max=200) List<@NotNull @Positive Long> ticketIds,
    @NotBlank @Size(max=255) String reason,
    @NotNull @AssertTrue Boolean cashConfirmed) {
    @Override public String toString() { return "ManagerOverrideRequest[REDACTED]"; }
}

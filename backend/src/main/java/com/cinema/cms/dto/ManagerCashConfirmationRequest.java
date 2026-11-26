package com.cinema.cms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ManagerCashConfirmationRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String overrideToken) {
    @Override public String toString() { return "ManagerCashConfirmationRequest[REDACTED]"; }
}

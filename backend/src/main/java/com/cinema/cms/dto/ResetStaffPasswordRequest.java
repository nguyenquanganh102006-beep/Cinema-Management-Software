package com.cinema.cms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetStaffPasswordRequest(@NotBlank @Size(min = 8, max = 72) String newPassword) {
    @Override public String toString() { return "ResetStaffPasswordRequest[REDACTED]"; }
}

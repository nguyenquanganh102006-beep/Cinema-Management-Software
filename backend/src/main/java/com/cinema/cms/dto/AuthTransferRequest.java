package com.cinema.cms.dto;
import com.cinema.cms.entity.UserRole;
import jakarta.validation.constraints.*;
public record AuthTransferRequest(@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String code,
                                  @NotNull UserRole role) {
    @Override public String toString() { return "AuthTransferRequest[redacted]"; }
}

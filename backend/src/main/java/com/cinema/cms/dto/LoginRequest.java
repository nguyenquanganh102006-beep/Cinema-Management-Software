package com.cinema.cms.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record LoginRequest(@NotBlank @Size(max = 150) String username,
                           @NotBlank @Size(max = 100) String password) {
    @Override public String toString() { return "LoginRequest[redacted]"; }
}

package com.cinema.cms.dto;
import jakarta.validation.constraints.*;
public record RegisterRequest(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{3,50}") String username,
        @NotBlank @Size(max = 100) String fullName,
        @NotBlank @Email @Size(max = 150) String email,
        @NotBlank @Pattern(regexp = "\\+?[0-9]{9,15}") String phone,
        @NotBlank @Size(min = 8, max = 72) String password) {
    public RegisterRequest {
        username = normalize(username); email = normalize(email);
        fullName = fullName == null ? null : fullName.strip();
        phone = phone == null ? null : phone.strip();
    }
    private static String normalize(String value) {
        return value == null ? null : value.strip().toLowerCase(java.util.Locale.ROOT);
    }
    @Override public String toString() { return "RegisterRequest[redacted]"; }
}

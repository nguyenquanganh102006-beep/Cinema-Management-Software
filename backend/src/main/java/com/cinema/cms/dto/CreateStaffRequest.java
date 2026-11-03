package com.cinema.cms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.Locale;

public record CreateStaffRequest(
        @Schema(example = "nguyen.van.a") @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{3,50}") String username,
        @Schema(example = "Nguyễn Văn A") @NotBlank @Size(max = 100) String fullName,
        @Schema(example = "a.nguyen@cinema.test") @NotBlank @Email @Size(max = 150) String email,
        @Schema(example = "0912345678") @NotBlank @Pattern(regexp = "\\+?[0-9]{9,15}") String phone,
        @Schema(description = "Mật khẩu ban đầu, 8 đến 72 byte", example = "ChangeMe#2026") @NotBlank @Size(min = 8, max = 72) String password) {

    public CreateStaffRequest {
        username = lower(username);
        email = lower(email);
        fullName = strip(fullName);
        phone = strip(phone);
    }

    private static String strip(String value) { return value == null ? null : value.strip(); }
    private static String lower(String value) { return value == null ? null : value.strip().toLowerCase(Locale.ROOT); }
    @Override public String toString() { return "CreateStaffRequest[redacted]"; }
}
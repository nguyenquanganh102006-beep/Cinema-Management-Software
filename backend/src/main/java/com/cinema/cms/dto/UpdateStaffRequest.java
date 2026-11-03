package com.cinema.cms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.Locale;

public record UpdateStaffRequest(
        @Schema(example = "Nguyễn Văn B") @NotBlank @Size(max = 100) String fullName,
        @Schema(example = "b.nguyen@cinema.test") @NotBlank @Email @Size(max = 150) String email,
        @Schema(example = "0987654321") @NotBlank @Pattern(regexp = "\\+?[0-9]{9,15}") String phone) {

    public UpdateStaffRequest {
        fullName = fullName == null ? null : fullName.strip();
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        phone = phone == null ? null : phone.strip();
    }
}
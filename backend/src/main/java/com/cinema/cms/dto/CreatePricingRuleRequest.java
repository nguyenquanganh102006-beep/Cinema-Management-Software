package com.cinema.cms.dto;

import com.cinema.cms.entity.PricingRuleType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.Locale;

public record CreatePricingRuleRequest(
        @Schema(example = "SUR_TIME") @NotBlank @Pattern(regexp = "[A-Z0-9_]{3,50}") String ruleCode,
        @Schema(example = "TIME_SURCHARGE") @NotNull PricingRuleType type,
        @Schema(description = "Đối tượng áp dụng, chỉ nhận các giá trị trong danh mục cho từng loại", example = "WEEKEND_OR_AFTER_18H") @NotBlank @Size(max = 50) String targetRef,
        @Schema(description = "Đồng, không âm, tối đa 8 chữ số nguyên và 2 chữ số thập phân", example = "10000") @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal amount,
        @Size(max = 255) String description) {

    public CreatePricingRuleRequest {
        ruleCode = ruleCode == null ? null : ruleCode.strip().toUpperCase(Locale.ROOT);
        targetRef = targetRef == null ? null : targetRef.strip().toUpperCase(Locale.ROOT);
        description = description == null || description.isBlank() ? null : description.strip();
    }
}
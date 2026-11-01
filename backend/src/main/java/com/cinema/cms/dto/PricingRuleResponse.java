package com.cinema.cms.dto;

import com.cinema.cms.entity.PricingRule;
import com.cinema.cms.entity.PricingRuleType;
import java.math.BigDecimal;

public record PricingRuleResponse(Integer id, String ruleCode, PricingRuleType type, String targetRef, BigDecimal amount, String description) {
    public static PricingRuleResponse from(PricingRule r) {
        return new PricingRuleResponse(r.getId(), r.getRuleCode(), r.getRuleType(), r.getTargetRef(), r.getAmount(), r.getDescription());
    }
}
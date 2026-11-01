package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.PricingRule;
import com.cinema.cms.entity.PricingRuleType;
import com.cinema.cms.repository.PricingRuleRepository;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricingRuleService {
    private final PricingRuleRepository rules;

    public PricingRuleService(PricingRuleRepository rules) { this.rules = rules; }

    @Transactional(readOnly = true)
    public List<PricingRuleResponse> list(String type) {
        List<PricingRule> found = type == null || type.isBlank() ? rules.findAllByOrderByRuleTypeAscTargetRefAsc()
                : rules.findByRuleTypeOrderByTargetRefAsc(parseType(type));
        return found.stream().map(PricingRuleResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public PricingRuleResponse get(Integer id) { return PricingRuleResponse.from(rules.findById(id).orElseThrow(PricingRuleService::notFound)); }

    @Transactional
    public PricingRuleResponse create(CreatePricingRuleRequest r) {
        if (!PricingRuleCatalog.accepts(r.type(), r.targetRef())) {
            throw new ApiException(400, "PRICING_RULE_INVALID", "Đối tượng áp dụng của " + r.type() + " chỉ nhận: " + String.join(", ", PricingRuleCatalog.targetsOf(r.type())));
        }
        rules.lockByRuleType(r.type());
        if (rules.existsByRuleCode(r.ruleCode())) throw new ApiException(409, "PRICING_RULE_CODE_EXISTS", "Mã quy tắc đã tồn tại");
        if (rules.existsByRuleTypeAndTargetRef(r.type(), r.targetRef())) throw new ApiException(409, "PRICING_RULE_EXISTS", "Đã có quy tắc cho " + r.type() + " / " + r.targetRef() + ", hãy sửa quy tắc đó");
        try {
            return PricingRuleResponse.from(rules.saveAndFlush(new PricingRule(r.ruleCode(), r.type(), r.targetRef(), r.amount().setScale(2, RoundingMode.UNNECESSARY), r.description())));
        } catch (DataIntegrityViolationException e) {
            String detail = e.getMostSpecificCause().getMessage();
            throw detail != null && detail.contains("rule_code") ? new ApiException(409, "PRICING_RULE_CODE_EXISTS", "Mã quy tắc đã tồn tại") : e;
        }
    }

    @Transactional
    public PricingRuleResponse update(Integer id, UpdatePricingRuleRequest r) {
        PricingRule rule = rules.findByIdForUpdate(id).orElseThrow(PricingRuleService::notFound);
        rule.update(r.amount().setScale(2, RoundingMode.UNNECESSARY), r.description());
        return PricingRuleResponse.from(rules.saveAndFlush(rule));
    }

    private static PricingRuleType parseType(String value) {
        try { return PricingRuleType.valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new ApiException(400, "VALIDATION_ERROR", "type phải là BASE_PRICE, ROOM_SURCHARGE, SEAT_SURCHARGE hoặc TIME_SURCHARGE"); }
    }
    private static ApiException notFound() { return new ApiException(404, "PRICING_RULE_NOT_FOUND", "Không tìm thấy quy tắc giá"); }
}
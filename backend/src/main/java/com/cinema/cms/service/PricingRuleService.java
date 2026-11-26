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
    private final AuditEventService auditEvents;

    public PricingRuleService(PricingRuleRepository rules, AuditEventService auditEvents) { this.rules = rules; this.auditEvents = auditEvents; }

    @Transactional(readOnly = true)
    public List<PricingRuleResponse> list(String type) {
        List<PricingRule> found = type == null || type.isBlank() ? rules.findAllByOrderByRuleTypeAscTargetRefAsc()
                : rules.findByRuleTypeOrderByTargetRefAsc(parseType(type));
        return found.stream().map(PricingRuleResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public PricingRuleResponse get(Integer id) { return PricingRuleResponse.from(rules.findById(id).orElseThrow(PricingRuleService::notFound)); }

    @Transactional
    public PricingRuleResponse create(CreatePricingRuleRequest r) { return create(r, null); }

    @Transactional
    public PricingRuleResponse create(CreatePricingRuleRequest r, Long actorUserId) {
        if (!PricingRuleCatalog.accepts(r.type(), r.targetRef())) {
            throw new ApiException(400, "PRICING_RULE_INVALID", "Đối tượng áp dụng của " + r.type() + " chỉ nhận: " + String.join(", ", PricingRuleCatalog.targetsOf(r.type())));
        }
        rules.lockByRuleType(r.type());
        if (rules.existsByRuleCode(r.ruleCode())) throw new ApiException(409, "PRICING_RULE_CODE_EXISTS", "Mã quy tắc đã tồn tại");
        if (rules.existsByRuleTypeAndTargetRef(r.type(), r.targetRef())) throw new ApiException(409, "PRICING_RULE_EXISTS", "Đã có quy tắc cho " + r.type() + " / " + r.targetRef() + ", hãy sửa quy tắc đó");
        try {
            PricingRule saved = rules.saveAndFlush(new PricingRule(r.ruleCode(), r.type(), r.targetRef(), r.amount().setScale(2, RoundingMode.UNNECESSARY), r.description()));
            auditEvents.record(actorUserId, "PRICING_RULE_CREATED", "SUCCESS", "PRICING_RULE", saved.getId(),
                    "ruleCode=" + saved.getRuleCode() + "; type=" + saved.getRuleType()
                            + "; targetRef=" + saved.getTargetRef() + "; amount=" + saved.getAmount());
            return PricingRuleResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            String detail = e.getMostSpecificCause().getMessage();
            throw detail != null && detail.contains("rule_code") ? new ApiException(409, "PRICING_RULE_CODE_EXISTS", "Mã quy tắc đã tồn tại") : e;
        }
    }

    @Transactional
    public PricingRuleResponse update(Integer id, UpdatePricingRuleRequest r) { return update(id, r, null); }

    @Transactional
    public PricingRuleResponse update(Integer id, UpdatePricingRuleRequest r, Long actorUserId) {
        PricingRule rule = rules.findByIdForUpdate(id).orElseThrow(PricingRuleService::notFound);
        var oldAmount = rule.getAmount();
        rule.update(r.amount().setScale(2, RoundingMode.UNNECESSARY), r.description());
        PricingRule saved = rules.saveAndFlush(rule);
        auditEvents.record(actorUserId, "PRICING_RULE_UPDATED", "SUCCESS", "PRICING_RULE", id,
                "ruleCode=" + saved.getRuleCode() + "; amount=" + oldAmount + "->" + saved.getAmount()
                        + "; description=\"" + text(saved.getDescription()) + "\"");
        return PricingRuleResponse.from(saved);
    }

    private static PricingRuleType parseType(String value) {
        try { return PricingRuleType.valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new ApiException(400, "VALIDATION_ERROR", "type phải là BASE_PRICE, ROOM_SURCHARGE, SEAT_SURCHARGE hoặc TIME_SURCHARGE"); }
    }
    private static ApiException notFound() { return new ApiException(404, "PRICING_RULE_NOT_FOUND", "Không tìm thấy quy tắc giá"); }
    private static String text(String value) { return value == null ? "" : value.replaceAll("[\\r\\n\\t]", " "); }
}

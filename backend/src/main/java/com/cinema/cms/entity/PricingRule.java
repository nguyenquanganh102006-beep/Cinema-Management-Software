package com.cinema.cms.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "pricing_rules")
public class PricingRule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id") private Integer id;
    @Column(name = "rule_code", nullable = false, length = 50) private String ruleCode;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "rule_type", nullable = false, columnDefinition = "pricing_rule_type_enum") private PricingRuleType ruleType;
    @Column(name = "target_ref", nullable = false, length = 50) private String targetRef;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal amount;
    @Column(length = 255) private String description;

    protected PricingRule() {}

    public PricingRule(String ruleCode, PricingRuleType ruleType, String targetRef, BigDecimal amount, String description) {
        this.ruleCode = ruleCode; this.ruleType = ruleType; this.targetRef = targetRef; update(amount, description);
    }

    public void update(BigDecimal amount, String description) { this.amount = amount; this.description = description; }

    public Integer getId() { return id; }
    public String getRuleCode() { return ruleCode; }
    public PricingRuleType getRuleType() { return ruleType; }
    public String getTargetRef() { return targetRef; }
    public BigDecimal getAmount() { return amount; }
    public String getDescription() { return description; }
}
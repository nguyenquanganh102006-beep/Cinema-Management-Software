package com.cinema.cms.repository;

import com.cinema.cms.entity.PricingRule;
import com.cinema.cms.entity.PricingRuleType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PricingRuleRepository extends JpaRepository<PricingRule, Integer> {
    List<PricingRule> findAllByOrderByRuleTypeAscTargetRefAsc();
    List<PricingRule> findByRuleTypeOrderByTargetRefAsc(PricingRuleType ruleType);
    boolean existsByRuleCode(String ruleCode);
    boolean existsByRuleTypeAndTargetRef(PricingRuleType ruleType, String targetRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PricingRule r where r.id = :id")
    Optional<PricingRule> findByIdForUpdate(@Param("id") Integer id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PricingRule r where r.ruleType = :type")
    List<PricingRule> lockByRuleType(@Param("type") PricingRuleType type);
}
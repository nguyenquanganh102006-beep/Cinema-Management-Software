package com.cinema.cms.service;

import com.cinema.cms.entity.PricingRuleType;
import java.util.Map;
import java.util.Set;

final class PricingRuleCatalog {
    private PricingRuleCatalog() {}

    private static final Map<PricingRuleType, Set<String>> TARGETS = Map.of(
            PricingRuleType.BASE_PRICE, Set.of("STANDARD_2D"),
            PricingRuleType.ROOM_SURCHARGE, Set.of("STANDARD_2D", "3D", "IMAX"),
            PricingRuleType.SEAT_SURCHARGE, Set.of("STANDARD", "VIP", "SWEETBOX"),
            PricingRuleType.TIME_SURCHARGE, Set.of("WEEKEND_OR_AFTER_18H"));

    static boolean accepts(PricingRuleType type, String targetRef) { return TARGETS.get(type).contains(targetRef); }
    static Set<String> targetsOf(PricingRuleType type) { return new java.util.TreeSet<>(TARGETS.get(type)); }
}
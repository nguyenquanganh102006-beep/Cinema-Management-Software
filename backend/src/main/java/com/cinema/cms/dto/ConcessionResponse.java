package com.cinema.cms.dto;

import com.cinema.cms.entity.ConcessionItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ConcessionResponse(Integer id, String name, BigDecimal price, boolean combo, boolean active, List<Component> components) {
    public record Component(Integer itemId, String name, int quantity) {}

    public static ConcessionResponse from(ConcessionItem item, Map<Integer, String> namesById) {
        List<Component> components = item.getComponents().entrySet().stream()
                .map(e -> new Component(e.getKey(), namesById.getOrDefault(e.getKey(), ""), e.getValue())).toList();
        return new ConcessionResponse(item.getId(), item.getName(), item.getPrice(), item.isCombo(), item.isActive(), components);
    }
}
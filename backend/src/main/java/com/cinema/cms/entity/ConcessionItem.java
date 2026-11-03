package com.cinema.cms.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "concession_items")
public class ConcessionItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id") private Integer id;
    @Column(nullable = false, length = 100) private String name;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal price;
    @Column(name = "is_combo", nullable = false) private boolean combo;
    @Column(name = "is_active", nullable = false) private boolean active;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "combo_items", joinColumns = @JoinColumn(name = "combo_id"))
    @MapKeyColumn(name = "component_item_id")
    @Column(name = "quantity", nullable = false)
    private Map<Integer, Integer> components = new LinkedHashMap<>();

    protected ConcessionItem() {}

    private ConcessionItem(String name, BigDecimal price, boolean combo) {
        this.name = name; this.price = price; this.combo = combo; this.active = true;
    }

    public static ConcessionItem simple(String name, BigDecimal price) { return new ConcessionItem(name, price, false); }

    public static ConcessionItem combo(String name, BigDecimal price, Map<Integer, Integer> components) {
        ConcessionItem combo = new ConcessionItem(name, price, true);
        combo.components.putAll(components);
        return combo;
    }

    public void update(String name, BigDecimal price) { this.name = name; this.price = price; }
    public void replaceComponents(Map<Integer, Integer> newComponents) { components.clear(); components.putAll(newComponents); }
    public void changeActive(boolean active) { this.active = active; }

    public Integer getId() { return id; }
    public String getName() { return name; }
    public BigDecimal getPrice() { return price; }
    public boolean isCombo() { return combo; }
    public boolean isActive() { return active; }
    public Map<Integer, Integer> getComponents() { return components; }
}
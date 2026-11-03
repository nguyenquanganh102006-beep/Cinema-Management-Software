package com.cinema.cms.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "concession_inventory")
public class ConcessionInventory {
    public static final int DEFAULT_THRESHOLD = 10;

    @Id @Column(name = "item_id") private Integer itemId;
    @MapsId @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id") private ConcessionItem item;
    @Column(name = "stock_quantity", nullable = false) private int stockQuantity;
    @Column(name = "low_stock_threshold", nullable = false) private int lowStockThreshold;

    protected ConcessionInventory() {}

    public ConcessionInventory(ConcessionItem item) { this.item = item; this.stockQuantity = 0; this.lowStockThreshold = DEFAULT_THRESHOLD; }

    public void setStockQuantity(int stockQuantity) { this.stockQuantity = stockQuantity; }

    public Integer getItemId() { return itemId; }
    public ConcessionItem getItem() { return item; }
    public int getStockQuantity() { return stockQuantity; }
    public int getLowStockThreshold() { return lowStockThreshold; }
}
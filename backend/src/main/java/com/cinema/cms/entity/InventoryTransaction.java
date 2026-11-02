package com.cinema.cms.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "inventory_transactions")
public class InventoryTransaction {
    public static final String RESTOCK = "RESTOCK";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "movement_id") private Long id;
    @Column(name = "item_id", nullable = false) private Integer itemId;
    @Column(name = "movement_type", nullable = false, length = 20) private String movementType;
    @Column(nullable = false) private int quantity;
    @Column(name = "before_quantity", nullable = false) private int beforeQuantity;
    @Column(name = "after_quantity", nullable = false) private int afterQuantity;
    @Column(name = "manager_id") private Long managerId;
    @Column(length = 500) private String note;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected InventoryTransaction() {}

    public static InventoryTransaction restock(Integer itemId, int quantity, int before, int after, Long managerId, String note) {
        InventoryTransaction t = new InventoryTransaction();
        t.itemId = itemId; t.movementType = RESTOCK; t.quantity = quantity; t.beforeQuantity = before; t.afterQuantity = after;
        t.managerId = managerId; t.note = note; t.createdAt = Instant.now();
        return t;
    }

    public Long getId() { return id; }
    public Integer getItemId() { return itemId; }
    public String getMovementType() { return movementType; }
    public int getQuantity() { return quantity; }
    public int getBeforeQuantity() { return beforeQuantity; }
    public int getAfterQuantity() { return afterQuantity; }
    public Long getManagerId() { return managerId; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
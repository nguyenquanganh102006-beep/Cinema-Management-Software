package com.cinema.cms.dto;

import com.cinema.cms.entity.InventoryTransaction;
import java.time.Instant;

public record InventoryMovementResponse(Long id, Integer itemId, String itemName, String type, int quantity, int beforeQuantity,
                                        int afterQuantity, Long managerId, String managerName, String note, Instant createdAt) {
    public static InventoryMovementResponse from(InventoryTransaction t, String itemName, String managerName) {
        return new InventoryMovementResponse(t.getId(), t.getItemId(), itemName, t.getMovementType(), t.getQuantity(), t.getBeforeQuantity(),
                t.getAfterQuantity(), t.getManagerId(), managerName, t.getNote(), t.getCreatedAt());
    }
}
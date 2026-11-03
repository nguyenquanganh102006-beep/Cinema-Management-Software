package com.cinema.cms.dto;

import com.cinema.cms.entity.ConcessionInventory;

public record InventoryResponse(Integer itemId, String name, boolean active, int stockQuantity, int lowStockThreshold, Status status) {
    public enum Status { IN_STOCK, LOW_STOCK, OUT_OF_STOCK }

    public static InventoryResponse from(ConcessionInventory i) {
        Status status = i.getStockQuantity() == 0 ? Status.OUT_OF_STOCK : i.getStockQuantity() <= i.getLowStockThreshold() ? Status.LOW_STOCK : Status.IN_STOCK;
        return new InventoryResponse(i.getItemId(), i.getItem().getName(), i.getItem().isActive(), i.getStockQuantity(), i.getLowStockThreshold(), status);
    }
}
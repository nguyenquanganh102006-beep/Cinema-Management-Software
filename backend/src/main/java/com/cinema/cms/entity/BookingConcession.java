package com.cinema.cms.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "booking_concessions")
public class BookingConcession {
    @EmbeddedId private BookingConcessionId id;
    @Column(nullable = false) private int quantity;
    @Column(name = "unit_price_snapshot", nullable = false, precision = 10, scale = 2) private BigDecimal unitPriceSnapshot;

    protected BookingConcession() {}

    public BookingConcession(Long bookingId, Integer itemId, int quantity, BigDecimal unitPriceSnapshot) {
        this.id = new BookingConcessionId(bookingId, itemId);
        this.quantity = quantity;
        this.unitPriceSnapshot = unitPriceSnapshot;
    }

    public BookingConcessionId getId() { return id; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPriceSnapshot() { return unitPriceSnapshot; }
}

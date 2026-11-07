package com.cinema.cms.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "booking_seats")
public class BookingSeat {
    @EmbeddedId private BookingSeatId id;
    @Column(name = "quoted_price", nullable = false, precision = 10, scale = 2) private BigDecimal quotedPrice;

    protected BookingSeat() {}

    public BookingSeat(Long bookingId, Long seatId, BigDecimal quotedPrice) {
        this.id = new BookingSeatId(bookingId, seatId);
        this.quotedPrice = quotedPrice;
    }

    public BookingSeatId getId() { return id; }
    public BigDecimal getQuotedPrice() { return quotedPrice; }
}

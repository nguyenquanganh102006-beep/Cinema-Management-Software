package com.cinema.cms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class BookingConcessionId implements Serializable {
    @Column(name = "booking_id") private Long bookingId;
    @Column(name = "item_id") private Integer itemId;

    protected BookingConcessionId() {}

    public BookingConcessionId(Long bookingId, Integer itemId) {
        this.bookingId = bookingId;
        this.itemId = itemId;
    }

    public Long getBookingId() { return bookingId; }
    public Integer getItemId() { return itemId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BookingConcessionId that)) return false;
        return Objects.equals(bookingId, that.bookingId) && Objects.equals(itemId, that.itemId);
    }

    @Override
    public int hashCode() { return Objects.hash(bookingId, itemId); }
}

package com.cinema.cms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class BookingSeatId implements Serializable {
    @Column(name = "booking_id") private Long bookingId;
    @Column(name = "seat_id") private Long seatId;

    protected BookingSeatId() {}

    public BookingSeatId(Long bookingId, Long seatId) {
        this.bookingId = bookingId;
        this.seatId = seatId;
    }

    public Long getBookingId() { return bookingId; }
    public Long getSeatId() { return seatId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BookingSeatId that)) return false;
        return Objects.equals(bookingId, that.bookingId) && Objects.equals(seatId, that.seatId);
    }

    @Override
    public int hashCode() { return Objects.hash(bookingId, seatId); }
}

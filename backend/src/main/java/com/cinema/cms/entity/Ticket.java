package com.cinema.cms.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tickets")
public class Ticket {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ticket_id") private Long id;
    @Column(name = "ticket_code", nullable = false, length = 50) private String ticketCode;
    @Column(name = "booking_id", nullable = false) private Long bookingId;
    @Column(name = "showtime_seat_id", nullable = false) private Long showtimeSeatId;
    @Column(name = "price_snapshot", nullable = false, precision = 10, scale = 2) private BigDecimal priceSnapshot;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "ticket_status_enum") private TicketStatus status;
    @Column(name = "issued_at") private Instant issuedAt;

    protected Ticket() {}

    public Ticket(String ticketCode, Long bookingId, Long showtimeSeatId, BigDecimal priceSnapshot, Clock clock) {
        this.ticketCode = ticketCode;
        this.bookingId = bookingId;
        this.showtimeSeatId = showtimeSeatId;
        this.priceSnapshot = priceSnapshot;
        this.status = TicketStatus.VALID;
        this.issuedAt = clock.instant();
    }

    public Long getId() { return id; }
    public String getTicketCode() { return ticketCode; }
    public Long getBookingId() { return bookingId; }
    public Long getShowtimeSeatId() { return showtimeSeatId; }
    public BigDecimal getPriceSnapshot() { return priceSnapshot; }
    public TicketStatus getStatus() { return status; }
    public Instant getIssuedAt() { return issuedAt; }
}

package com.cinema.cms.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "bookings")
public class Booking {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "booking_id") private Long id;
    @Column(name = "booking_code", nullable = false, length = 30) private String bookingCode;
    @Column(name = "showtime_id", nullable = false) private Long showtimeId;
    @Column(name = "user_id") private Long userId;
    @Column(name = "staff_id") private Long staffId;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "booking_channel_enum") private BookingChannel channel;
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2) private BigDecimal totalAmount;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "booking_status_enum") private BookingStatus status;
    @Column(name = "created_at") private Instant createdAt;
    @Column(name = "age_confirmed_at") private Instant ageConfirmedAt;
    @Column(name = "hold_token", length = 100) private String holdToken;
    @Column(name = "hold_owner", length = 100) private String holdOwner;
    @Column(name = "ticket_total", precision = 12, scale = 2) private BigDecimal ticketTotal;
    @Column(name = "concession_total", precision = 12, scale = 2) private BigDecimal concessionTotal;
    @Column(name = "paid_at") private Instant paidAt;
    @Version @Column(nullable = false) private Integer version;

    protected Booking() {}

    public Booking(String bookingCode, Long showtimeId, Long userId, Long staffId, BookingChannel channel,
                   BigDecimal totalAmount, BigDecimal ticketTotal, BigDecimal concessionTotal,
                   String holdToken, String holdOwner, Instant ageConfirmedAt, Clock clock) {
        this.bookingCode = bookingCode;
        this.showtimeId = showtimeId;
        this.userId = userId;
        this.staffId = staffId;
        this.channel = channel;
        this.totalAmount = totalAmount;
        this.ticketTotal = ticketTotal;
        this.concessionTotal = concessionTotal;
        this.holdToken = holdToken;
        this.holdOwner = holdOwner;
        this.ageConfirmedAt = ageConfirmedAt;
        this.status = BookingStatus.PENDING;
        this.createdAt = clock.instant();
    }

    public void markPaid(Instant now) {
        this.status = BookingStatus.PAID;
        this.paidAt = now;
    }

    public Long getId() { return id; }
    public String getBookingCode() { return bookingCode; }
    public Long getShowtimeId() { return showtimeId; }
    public Long getUserId() { return userId; }
    public Long getStaffId() { return staffId; }
    public BookingChannel getChannel() { return channel; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BookingStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAgeConfirmedAt() { return ageConfirmedAt; }
    public String getHoldToken() { return holdToken; }
    public String getHoldOwner() { return holdOwner; }
    public BigDecimal getTicketTotal() { return ticketTotal; }
    public BigDecimal getConcessionTotal() { return concessionTotal; }
    public Instant getPaidAt() { return paidAt; }
    public Integer getVersion() { return version; }
}

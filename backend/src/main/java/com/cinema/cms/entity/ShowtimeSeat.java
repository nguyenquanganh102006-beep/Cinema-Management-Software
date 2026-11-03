package com.cinema.cms.entity;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "showtime_seats", uniqueConstraints = @UniqueConstraint(name = "uq_showtime_seat", columnNames = {"showtime_id", "seat_id"}))
public class ShowtimeSeat {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "showtime_seat_id") private Long id;
    @Column(name = "showtime_id", nullable = false) private Long showtimeId;
    @Column(name = "seat_id", nullable = false) private Long seatId;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "seat_status_enum") private SeatStatus status;
    @Column(name = "owner_id", length = 100) private String ownerId;
    @Column(name = "hold_token", length = 100) private String holdToken;
    @Column(name = "hold_until") private Instant holdUntil;
    @Column(name = "extension_used", nullable = false) private boolean extensionUsed;
    @Version @Column(nullable = false) private Integer version;
    @Column(name = "updated_at") private Instant updatedAt;

    protected ShowtimeSeat() {}

    public ShowtimeSeat(Long showtimeId, Long seatId, Instant initializedAt) {
        this.showtimeId = showtimeId; this.seatId = seatId;
        this.status = SeatStatus.AVAILABLE; this.updatedAt = initializedAt;
    }

    public Long getId() { return id; }
    public Long getShowtimeId() { return showtimeId; }
    public Long getSeatId() { return seatId; }
    public SeatStatus getStatus() { return status; }
    public String getOwnerId() { return ownerId; }
    public String getHoldToken() { return holdToken; }
    public Instant getHoldUntil() { return holdUntil; }
    public boolean isExtensionUsed() { return extensionUsed; }
    public Integer getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}

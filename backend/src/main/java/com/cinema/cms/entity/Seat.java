package com.cinema.cms.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "seats")
public class Seat {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seat_id") private Long id;
    @Column(name = "auditorium_id", nullable = false) private Integer auditoriumId;
    @Column(name = "seat_row", nullable = false, length = 5) private String seatRow;
    @Column(name = "seat_number", nullable = false) private int seatNumber;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "seat_type", nullable = false, columnDefinition = "seat_type_enum") private SeatType seatType;
    @Column(name = "pair_seat_id") private Long pairSeatId;

    protected Seat() {}

    public Seat(Integer auditoriumId, String seatRow, int seatNumber, SeatType seatType) {
        this.auditoriumId = auditoriumId; update(seatRow, seatNumber, seatType);
    }

    public void update(String seatRow, int seatNumber, SeatType seatType) {
        this.seatRow = seatRow; this.seatNumber = seatNumber; this.seatType = seatType;
    }

    public Long getId() { return id; }
    public Integer getAuditoriumId() { return auditoriumId; }
    public String getSeatRow() { return seatRow; }
    public int getSeatNumber() { return seatNumber; }
    public SeatType getSeatType() { return seatType; }
    public Long getPairSeatId() { return pairSeatId; }
}
package com.cinema.cms.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "showtimes")
public class Showtime {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "showtime_id") private Long id;
    @Column(name = "movie_id", nullable = false) private Long movieId;
    @Column(name = "auditorium_id", nullable = false) private Integer auditoriumId;
    @Column(name = "start_time", nullable = false) private Instant startTime;
    @Column(name = "end_time", nullable = false) private Instant endTime;
    @Column(name = "base_price", nullable = false, precision = 10, scale = 2) private BigDecimal basePrice;

    protected Showtime() {}

    public Showtime(Long movieId, Integer auditoriumId, Instant startTime, Instant endTime, BigDecimal basePrice) {
        this.auditoriumId = auditoriumId; this.basePrice = basePrice;
        update(movieId, startTime, endTime);
    }

    public void update(Long movieId, Instant startTime, Instant endTime) {
        this.movieId = movieId; this.startTime = startTime; this.endTime = endTime;
    }

    public Long getId() { return id; }
    public Long getMovieId() { return movieId; }
    public Integer getAuditoriumId() { return auditoriumId; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public BigDecimal getBasePrice() { return basePrice; }
}

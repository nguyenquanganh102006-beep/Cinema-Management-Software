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
    @Column(name = "movie_title_snapshot", nullable = false, length = 200) private String movieTitleSnapshot;
    @Enumerated(EnumType.STRING) @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NAMED_ENUM)
    @Column(name = "age_rating_snapshot", nullable = false, columnDefinition = "age_rating_enum") private AgeRating ageRatingSnapshot;
    @Column(name = "duration_minutes_snapshot", nullable = false) private int durationMinutesSnapshot;
    @Column(name = "auditorium_id", nullable = false) private Integer auditoriumId;
    @Column(name = "auditorium_name_snapshot", nullable = false, length = 50) private String auditoriumNameSnapshot;
    @Column(name = "auditorium_type_snapshot", nullable = false, length = 20) private String auditoriumTypeSnapshot;
    @Column(name = "start_time", nullable = false) private Instant startTime;
    @Column(name = "end_time", nullable = false) private Instant endTime;
    @Column(name = "base_price", nullable = false, precision = 10, scale = 2) private BigDecimal basePrice;

    protected Showtime() {}

    public Showtime(Movie movie, Auditorium auditorium, Instant startTime, Instant endTime, BigDecimal basePrice) {
        this.auditoriumId = auditorium.getId(); this.basePrice = basePrice;
        update(movie, auditorium, startTime, endTime);
    }

    public void update(Movie movie, Auditorium auditorium, Instant startTime, Instant endTime) {
        this.movieId = movie.getId();
        this.movieTitleSnapshot = movie.getTitle();
        this.ageRatingSnapshot = movie.getAgeRating();
        this.durationMinutesSnapshot = movie.getDurationMinutes();
        this.auditoriumNameSnapshot = auditorium.getName();
        this.auditoriumTypeSnapshot = auditorium.getType().value();
        this.startTime = startTime; this.endTime = endTime;
    }

    public Long getId() { return id; }
    public Long getMovieId() { return movieId; }
    public String getMovieTitleSnapshot() { return movieTitleSnapshot; }
    public AgeRating getAgeRatingSnapshot() { return ageRatingSnapshot; }
    public int getDurationMinutesSnapshot() { return durationMinutesSnapshot; }
    public Integer getAuditoriumId() { return auditoriumId; }
    public String getAuditoriumNameSnapshot() { return auditoriumNameSnapshot; }
    public String getAuditoriumTypeSnapshot() { return auditoriumTypeSnapshot; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public BigDecimal getBasePrice() { return basePrice; }
}

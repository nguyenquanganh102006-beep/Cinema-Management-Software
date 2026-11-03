package com.cinema.cms.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "movies")
public class Movie {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "movie_id") private Long id;
    @Column(nullable = false, length = 200) private String title;
    @Column(name = "duration_minutes", nullable = false) private int durationMinutes;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "age_rating", nullable = false, columnDefinition = "age_rating_enum") private AgeRating ageRating;
    @Column(name = "release_date", nullable = false) private LocalDate releaseDate;
    @Column(name = "is_active", nullable = false) private boolean active;
    @Column(columnDefinition = "text") private String description;
    @Column(length = 150) private String genre;
    @Column(length = 100) private String country;
    @Column(length = 100) private String language;
    @Column(length = 150) private String director;
    @Column(length = 2048) private String poster;
    @Column(length = 2048) private String trailer;

    protected Movie() {}

    public Movie(String title, int durationMinutes, AgeRating ageRating, LocalDate releaseDate, String description,
                 String genre, String country, String language, String director, String poster, String trailer) {
        update(title, durationMinutes, ageRating, releaseDate, description, genre, country, language, director, poster, trailer);
        this.active = true;
    }

    public void update(String title, int durationMinutes, AgeRating ageRating, LocalDate releaseDate, String description,
                       String genre, String country, String language, String director, String poster, String trailer) {
        this.title = title; this.durationMinutes = durationMinutes; this.ageRating = ageRating; this.releaseDate = releaseDate;
        this.description = description; this.genre = genre; this.country = country; this.language = language;
        this.director = director; this.poster = poster; this.trailer = trailer;
    }

    public void changeActive(boolean active) { this.active = active; }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public int getDurationMinutes() { return durationMinutes; }
    public AgeRating getAgeRating() { return ageRating; }
    public LocalDate getReleaseDate() { return releaseDate; }
    public boolean isActive() { return active; }
    public String getDescription() { return description; }
    public String getGenre() { return genre; }
    public String getCountry() { return country; }
    public String getLanguage() { return language; }
    public String getDirector() { return director; }
    public String getPoster() { return poster; }
    public String getTrailer() { return trailer; }
}
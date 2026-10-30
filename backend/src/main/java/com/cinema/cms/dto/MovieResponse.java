package com.cinema.cms.dto;

import com.cinema.cms.entity.AgeRating;
import com.cinema.cms.entity.Movie;
import java.time.LocalDate;

public record MovieResponse(Long id, String title, int durationMinutes, AgeRating ageRating, LocalDate releaseDate,
                            boolean active, String description, String genre, String country, String language,
                            String director, String poster, String trailer) {
    public static MovieResponse from(Movie m) {
        return new MovieResponse(m.getId(), m.getTitle(), m.getDurationMinutes(), m.getAgeRating(), m.getReleaseDate(),
                m.isActive(), m.getDescription(), m.getGenre(), m.getCountry(), m.getLanguage(), m.getDirector(),
                m.getPoster(), m.getTrailer());
    }
}
package com.cinema.cms.dto;

import com.cinema.cms.entity.*;
import com.cinema.cms.service.ShowtimeTimeService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ShowtimeResponse(Long id, Long movieId, Integer auditoriumId,
                               OffsetDateTime startTime, OffsetDateTime endTime, BigDecimal basePrice,
                               String movieTitle, AgeRating ageRating, int durationMinutes, String auditoriumName, String auditoriumType) {
    public static ShowtimeResponse from(Showtime s, Movie movie, Auditorium room) {
        return new ShowtimeResponse(s.getId(), s.getMovieId(), s.getAuditoriumId(),
                s.getStartTime().atZone(ShowtimeTimeService.BUSINESS_ZONE).toOffsetDateTime(),
                s.getEndTime().atZone(ShowtimeTimeService.BUSINESS_ZONE).toOffsetDateTime(), s.getBasePrice(), movie.getTitle(), movie.getAgeRating(), movie.getDurationMinutes(), room.getName(), room.getType().value());
    }
}

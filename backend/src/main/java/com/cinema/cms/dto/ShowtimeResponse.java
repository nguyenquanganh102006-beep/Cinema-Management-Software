package com.cinema.cms.dto;

import com.cinema.cms.entity.AgeRating;
import com.cinema.cms.entity.Showtime;
import com.cinema.cms.service.ShowtimeTimeService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ShowtimeResponse(Long id, Long movieId, Integer auditoriumId,
                               OffsetDateTime startTime, OffsetDateTime endTime, BigDecimal basePrice,
                               String movieTitle, AgeRating ageRating, int durationMinutes, String auditoriumName, String auditoriumType) {
    public static ShowtimeResponse from(Showtime s) {
        return new ShowtimeResponse(s.getId(), s.getMovieId(), s.getAuditoriumId(),
                s.getStartTime().atZone(ShowtimeTimeService.BUSINESS_ZONE).toOffsetDateTime(),
                s.getEndTime().atZone(ShowtimeTimeService.BUSINESS_ZONE).toOffsetDateTime(), s.getBasePrice(),
                s.getMovieTitleSnapshot(), s.getAgeRatingSnapshot(), s.getDurationMinutesSnapshot(),
                s.getAuditoriumNameSnapshot(), s.getAuditoriumTypeSnapshot());
    }
}

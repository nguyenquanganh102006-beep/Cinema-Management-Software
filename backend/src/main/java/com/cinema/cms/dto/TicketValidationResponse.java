package com.cinema.cms.dto;

import com.cinema.cms.entity.AgeRating;
import java.time.Instant;

/** Result of a successful gate scan. The ticket has already moved to USED. */
public record TicketValidationResponse(
        Long ticketId,
        String ticketCode,
        String status,
        Long bookingId,
        String bookingCode,
        Long showtimeId,
        Long movieId,
        String movieTitle,
        AgeRating ageRating,
        Long seatId,
        String seatRow,
        Integer seatNumber,
        Instant usedAt) {}

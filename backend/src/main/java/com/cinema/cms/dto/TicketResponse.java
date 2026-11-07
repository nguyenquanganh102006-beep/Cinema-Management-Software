package com.cinema.cms.dto;

import com.cinema.cms.entity.AgeRating;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Ticket read model for STAFF and the owning CUSTOMER. */
public record TicketResponse(
        Long ticketId,
        String ticketCode,
        BigDecimal priceSnapshot,
        String status,
        Instant issuedAt,
        Instant usedAt,
        Long bookingId,
        String bookingCode,
        String bookingStatus,
        String bookingChannel,
        Long showtimeId,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        Long movieId,
        String movieTitle,
        AgeRating ageRating,
        Integer auditoriumId,
        String auditoriumName,
        Long seatId,
        String seatRow,
        Integer seatNumber,
        String seatType) {}

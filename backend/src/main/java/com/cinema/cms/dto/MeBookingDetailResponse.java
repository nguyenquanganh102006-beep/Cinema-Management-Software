package com.cinema.cms.dto;

import com.cinema.cms.entity.AgeRating;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/** CUSTOMER-owned booking detail with tickets, F&B and screening information. */
public record MeBookingDetailResponse(
        Long bookingId,
        String bookingCode,
        String status,
        String channel,
        BigDecimal totalAmount,
        BigDecimal ticketTotal,
        BigDecimal concessionTotal,
        Instant createdAt,
        Instant paidAt,
        ShowtimeInfo showtime,
        List<TicketLine> tickets,
        List<ConcessionLine> concessions,
        PaymentInfo payment) {

    public record ShowtimeInfo(
            Long showtimeId,
            Long movieId,
            String movieTitle,
            AgeRating ageRating,
            Integer auditoriumId,
            String auditoriumName,
            OffsetDateTime startTime,
            OffsetDateTime endTime) {}

    public record TicketLine(
            Long ticketId,
            String ticketCode,
            Long seatId,
            String seatRow,
            Integer seatNumber,
            String seatType,
            BigDecimal priceSnapshot,
            String status,
            Instant issuedAt,
            Instant usedAt) {}

    public record ConcessionLine(
            Integer itemId,
            String itemName,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal) {}

    public record PaymentInfo(
            String method, String status, BigDecimal amount, Instant paidAt) {}
}

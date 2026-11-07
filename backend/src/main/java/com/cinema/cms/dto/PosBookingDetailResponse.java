package com.cinema.cms.dto;

import com.cinema.cms.entity.AgeRating;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/** Full counter view of a POS booking, assembled from Booking/Payment/Ticket rows. */
public record PosBookingDetailResponse(
        Long bookingId,
        String bookingCode,
        String channel,
        String status,
        BigDecimal ticketTotal,
        BigDecimal concessionTotal,
        BigDecimal totalAmount,
        Instant createdAt,
        Instant paidAt,
        ShowtimeInfo showtime,
        List<SeatLine> seats,
        List<TicketLine> tickets,
        List<ConcessionLine> concessions,
        PaymentInfo payment,
        StaffInfo performedBy) {

    public record ShowtimeInfo(
            Long showtimeId,
            Long movieId,
            String movieTitle,
            AgeRating ageRating,
            Integer auditoriumId,
            String auditoriumName,
            OffsetDateTime startTime,
            OffsetDateTime endTime) {}

    public record SeatLine(
            Long seatId,
            String seatRow,
            Integer seatNumber,
            String seatType,
            BigDecimal quotedPrice) {}

    public record TicketLine(
            Long ticketId,
            String ticketCode,
            Long showtimeSeatId,
            Long seatId,
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
            Long paymentId,
            String method,
            String status,
            BigDecimal amount,
            BigDecimal cashReceived,
            Instant paidAt,
            Long confirmedByStaffId) {}

    public record StaffInfo(Long staffId, String fullName) {}
}

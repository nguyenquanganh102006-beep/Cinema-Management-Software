package com.cinema.cms.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
public record RefundEligibilityResponse(Long bookingId, String bookingCode, String channel,
    Long showtimeId, Instant startsAt, Instant serverTime, List<TicketLine> tickets) {
    public record TicketLine(Long ticketId, String ticketCode, Long seatId, String row,
        int seatNumber, String status, BigDecimal priceSnapshot, boolean eligible,
        String reasonCode, BigDecimal refundableAmount) {}
}

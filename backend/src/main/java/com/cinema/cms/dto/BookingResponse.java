package com.cinema.cms.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BookingResponse(
        Long bookingId,
        String bookingCode,
        String status,
        List<TicketLine> tickets,
        List<ConcessionLine> concessions,
        BigDecimal totalAmount,
        Instant holdUntil) {

    public record TicketLine(Long seatId, BigDecimal price) {}

    public record ConcessionLine(Integer itemId, Integer quantity, BigDecimal unitPrice) {}
}

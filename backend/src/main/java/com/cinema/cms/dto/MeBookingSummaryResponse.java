package com.cinema.cms.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** One row in a CUSTOMER's own booking history. */
public record MeBookingSummaryResponse(
        Long bookingId,
        String bookingCode,
        String status,
        String channel,
        BigDecimal totalAmount,
        Instant createdAt,
        Instant paidAt,
        Long showtimeId) {}

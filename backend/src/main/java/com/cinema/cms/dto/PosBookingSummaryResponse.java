package com.cinema.cms.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** One paid POS booking in the counter history. */
public record PosBookingSummaryResponse(
        Long bookingId,
        String bookingCode,
        Instant paidAt,
        BigDecimal totalAmount,
        String paymentMethod,
        String paymentStatus,
        long ticketCount,
        String refundStatus) {}

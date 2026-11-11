package com.cinema.cms.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
public record RefundResponse(Long refundId, Long bookingId, BigDecimal refundAmount, List<Long> ticketIds,
    String status, Instant cashConfirmedAt, String refundStatus) {}

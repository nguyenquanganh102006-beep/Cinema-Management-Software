package com.cinema.cms.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Result of a successful POS cash payment. */
public record CashPaymentResponse(
        Long paymentId,
        Long bookingId,
        String bookingCode,
        BigDecimal totalAmount,
        BigDecimal cashReceived,
        BigDecimal change,
        String paymentStatus,
        Instant paidAt,
        List<PaymentTicketResponse> tickets) {}

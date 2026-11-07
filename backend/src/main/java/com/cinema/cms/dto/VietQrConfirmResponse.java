package com.cinema.cms.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Result of a STAFF-confirmed VietQR transfer. Tickets are issued only after this step commits. */
public record VietQrConfirmResponse(
        Long paymentId,
        Long bookingId,
        String bookingCode,
        BigDecimal amount,
        String paymentStatus,
        Long confirmedByStaffId,
        Instant paidAt,
        List<PaymentTicketResponse> tickets) {}

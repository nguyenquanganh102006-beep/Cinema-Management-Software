package com.cinema.cms.dto;

import java.math.BigDecimal;

/** Read-only status shown to the customer portal after VNPay redirects back. */
public record VnPayReturnResponse(
        Long paymentId,
        Long bookingId,
        String bookingCode,
        String vnpTxnRef,
        String vnpResponseCode,
        String vnpTransactionNo,
        BigDecimal amount,
        String paymentStatus,
        String bookingStatus) {}

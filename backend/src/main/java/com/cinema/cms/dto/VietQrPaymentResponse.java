package com.cinema.cms.dto;

import java.math.BigDecimal;

/** Transfer instructions for a PENDING VIETQR payment. The booking is not paid yet. */
public record VietQrPaymentResponse(
        Long paymentId,
        Long bookingId,
        String bookingCode,
        BigDecimal amount,
        String bankAccount,
        String bankName,
        String transferContent,
        String qrPayload,
        String paymentStatus) {}

package com.cinema.cms.dto;

import java.time.Instant;

/** VNPay redirect URL and the moment the merchant reference stops being valid. */
public record VnPayPaymentResponse(String paymentUrl, Instant expiresAt) {}

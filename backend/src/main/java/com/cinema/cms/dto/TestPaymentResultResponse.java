package com.cinema.cms.dto;

public record TestPaymentResultResponse(Long bookingId, String paymentStatus, int ticketCount) {}

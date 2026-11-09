package com.cinema.cms.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** A ticket issued by a completed payment. One physical seat, including each Sweetbox seat. */
public record PaymentTicketResponse(
        Long ticketId,
        String ticketCode,
        Long showtimeSeatId,
        BigDecimal price,
        String status,
        Instant issuedAt) {}

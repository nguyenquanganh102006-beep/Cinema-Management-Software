package com.cinema.cms.dto;

import java.math.BigDecimal;

public record TicketPrice(
        Long seatId,
        BigDecimal basePrice,
        BigDecimal roomSurcharge,
        BigDecimal seatSurcharge,
        BigDecimal timeSurcharge,
        BigDecimal finalPrice) {}

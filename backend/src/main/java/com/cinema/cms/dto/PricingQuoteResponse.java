package com.cinema.cms.dto;

import java.math.BigDecimal;
import java.util.List;

public record PricingQuoteResponse(
        Long showtimeId, List<TicketPrice> seats, BigDecimal ticketTotal) {}

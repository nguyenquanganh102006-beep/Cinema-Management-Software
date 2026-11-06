package com.cinema.cms.dto;

import java.time.Instant;
import java.util.List;

public record HoldSeatsResponse(
    String holdToken,
    Long showtimeId,
    List<Long> seatIds,
    Instant serverTime,
    Instant holdUntil,
    boolean extensionUsed) {}

package com.cinema.cms.dto;

import com.cinema.cms.entity.*;
import java.time.Instant;
import java.util.List;

public record SeatMapResponse(
    Long showtimeId, Instant serverTime, List<SeatView> seats, List<HoldSeatsResponse> ownHolds) {
  public record SeatView(
      Long seatId,
      String row,
      int number,
      SeatType seatType,
      Long pairSeatId,
      SeatStatus status,
      boolean heldByMe) {}
}

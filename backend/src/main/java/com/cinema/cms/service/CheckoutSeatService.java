package com.cinema.cms.service;

import com.cinema.cms.entity.ShowtimeSeat;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** E supplies the authenticated owner and planned seat IDs persisted in Booking. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CheckoutSeatService {
  private final SeatHoldService holds;
  private final Clock clock;

  public CheckoutSeatService(SeatHoldService holds, Clock clock) {
    this.holds = holds;
    this.clock = clock;
  }

  public List<ShowtimeSeat> lockAndValidateHold(
      String token, String owner, Long showtimeId, Collection<Long> expectedSeatIds) {
    SeatHoldService.validateShowtimeId(showtimeId);
    if (expectedSeatIds == null
        || expectedSeatIds.isEmpty()
        || expectedSeatIds.size() > 100
        || expectedSeatIds.stream().anyMatch(id -> id == null || id <= 0)
        || new HashSet<>(expectedSeatIds).size() != expectedSeatIds.size())
      throw new ApiException(400, "VALIDATION_ERROR", "Danh sách ghế dự kiến không hợp lệ");
    var group = holds.lockToken(token);
    var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (group.isEmpty() || group.stream().anyMatch(s -> !s.live(now)))
      throw SeatHoldService.expired();
    SeatHoldService.checkOwner(group, owner);
    if (group.stream().anyMatch(s -> !showtimeId.equals(s.getShowtimeId()))
        || !new HashSet<>(expectedSeatIds)
            .equals(new HashSet<>(group.stream().map(ShowtimeSeat::getSeatId).toList())))
      throw new ApiException(409, "SEAT_HOLD_MISMATCH", "Nhóm ghế không khớp với Booking");
    SeatHoldService.checkConsistent(group);
    return group;
  }

  public List<ShowtimeSeat> markBooked(
      String token, String owner, Long showtimeId, Collection<Long> expectedSeatIds) {
    var group = lockAndValidateHold(token, owner, showtimeId, expectedSeatIds);
    var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (group.stream().anyMatch(s -> !s.live(now))) throw SeatHoldService.expired();
    group.forEach(s -> s.book(now));
    return group;
  }
}

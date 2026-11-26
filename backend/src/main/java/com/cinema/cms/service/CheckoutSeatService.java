package com.cinema.cms.service;

import com.cinema.cms.entity.ShowtimeSeat;
import com.cinema.cms.entity.SeatStatus;
import com.cinema.cms.repository.ShowtimeSeatRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** E supplies the authenticated owner and planned seat IDs persisted in Booking. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CheckoutSeatService {
  private final SeatHoldService holds;
  private final ShowtimeSeatRepository inventory;
  private final Clock clock;

  public CheckoutSeatService(SeatHoldService holds, ShowtimeSeatRepository inventory, Clock clock) {
    this.holds = holds;
    this.inventory = inventory;
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

  /**
   * Finalizes a successful online IPN without throwing on the expired-hold branch. The IPN
   * transaction must stay usable while it safely checks whether those seats can be recovered.
   */
  public List<ShowtimeSeat> markBookedForOnlineIpn(
      String token, String owner, Long showtimeId, Collection<Long> expectedSeatIds) {
    SeatHoldService.validateShowtimeId(showtimeId);
    if (expectedSeatIds == null
        || expectedSeatIds.isEmpty()
        || expectedSeatIds.size() > 100
        || expectedSeatIds.stream().anyMatch(id -> id == null || id <= 0)
        || new HashSet<>(expectedSeatIds).size() != expectedSeatIds.size())
      throw new ApiException(400, "VALIDATION_ERROR", "Danh sách ghế dự kiến không hợp lệ");

    var group = holds.lockToken(token);
    if (group.isEmpty()) return markBookedAfterExpiredHold(showtimeId, expectedSeatIds);

    SeatHoldService.checkOwner(group, owner);
    if (group.stream().anyMatch(s -> !showtimeId.equals(s.getShowtimeId()))
        || !new HashSet<>(expectedSeatIds)
            .equals(new HashSet<>(group.stream().map(ShowtimeSeat::getSeatId).toList())))
      throw new ApiException(409, "SEAT_HOLD_MISMATCH", "Nhóm ghế không khớp với Booking");
    SeatHoldService.checkConsistent(group);

    var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (group.stream().anyMatch(s -> !s.live(now)))
      return markBookedAfterExpiredHold(showtimeId, expectedSeatIds);

    group.forEach(seat -> seat.book(now));
    return group;
  }

  /**
   * Handles a successful online IPN received after its hold expired. The original seats can be
   * recovered only when every row is still AVAILABLE or has an expired hold; a live hold or a
   * completed sale by another customer wins the row lock and prevents issuing tickets here.
   */
  public List<ShowtimeSeat> markBookedAfterExpiredHold(
      Long showtimeId, Collection<Long> expectedSeatIds) {
    SeatHoldService.validateShowtimeId(showtimeId);
    if (expectedSeatIds == null
        || expectedSeatIds.isEmpty()
        || expectedSeatIds.size() > 100
        || expectedSeatIds.stream().anyMatch(id -> id == null || id <= 0)
        || new HashSet<>(expectedSeatIds).size() != expectedSeatIds.size())
      throw new ApiException(400, "VALIDATION_ERROR", "Danh sách ghế dự kiến không hợp lệ");

    inventory.configureLockTimeout();
    var sortedIds = new TreeSet<>(expectedSeatIds);
    var group = inventory.lockSeats(showtimeId, sortedIds);
    var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (group.size() != sortedIds.size()
        || group.stream().anyMatch(
            seat -> seat.getStatus() == SeatStatus.BOOKED
                || seat.getStatus() == SeatStatus.HOLDING && seat.live(now)))
      throw new ApiException(409, "SEAT_NOT_AVAILABLE", "Ghế đã được người khác giữ hoặc bán");

    group.forEach(seat -> seat.book(now));
    return group;
  }
}

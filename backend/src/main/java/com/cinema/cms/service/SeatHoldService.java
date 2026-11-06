package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SeatHoldService {
  private final ShowtimeSeatRepository inventory;
  private final SeatRepository seats;
  private final ShowtimeRepository showtimes;
  private final HoldOwnershipService ownership;
  private final SweetboxPairValidator pairs;
  private final Clock clock;
  private final long ttl, extension, after;

  public SeatHoldService(
      ShowtimeSeatRepository inventory,
      SeatRepository seats,
      ShowtimeRepository showtimes,
      HoldOwnershipService ownership,
      SweetboxPairValidator pairs,
      Clock clock,
      @Value("${app.holds.ttl-seconds:300}") long ttl,
      @Value("${app.holds.extension-seconds:120}") long extension,
      @Value("${app.holds.extend-after-seconds:240}") long after) {
    if (ttl <= 0 || extension <= 0 || after < 0 || after >= ttl)
      throw new IllegalArgumentException("Invalid seat hold timing configuration");
    this.inventory = inventory;
    this.seats = seats;
    this.showtimes = showtimes;
    this.ownership = ownership;
    this.pairs = pairs;
    this.clock = clock;
    this.ttl = ttl;
    this.extension = extension;
    this.after = after;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public HoldSeatsResponse hold(
      Long showtimeId, HoldSeatsRequest request, SessionPrincipal principal) {
    String owner = ownership.owner(principal);
    validateShowtimeId(showtimeId);
    var ids = request == null ? null : request.seatIds();
    if (ids == null
        || ids.isEmpty()
        || ids.size() > 100
        || ids.stream().anyMatch(i -> i == null || i <= 0)
        || new HashSet<>(ids).size() != ids.size())
      throw error(400, "VALIDATION_ERROR", "Danh sách ghế không hợp lệ");

    // Scalar only before locking: C may PATCH this showtime while D waits.
    Integer room =
        showtimes
            .findAuditoriumId(showtimeId)
            .orElseThrow(() -> error(404, "SHOWTIME_NOT_FOUND", "Không tìm thấy suất chiếu"));
    var physical = seats.findByAuditoriumIdOrderBySeatRowAscSeatNumberAsc(room);
    if (!pairs.validateLayout(physical))
      throw error(409, "SWEETBOX_PAIR_INVALID", "Cặp Sweetbox chưa được cấu hình đúng");
    var byId = new HashMap<Long, Seat>();
    physical.forEach(s -> byId.put(s.getId(), s));
    var expanded = new TreeSet<Long>();
    for (Long id : ids) {
      Seat seat = byId.get(id);
      if (seat == null) throw error(400, "INVALID_SEAT", "Ghế không thuộc suất chiếu");
      expanded.add(id);
      if (seat.getSeatType() == SeatType.SWEETBOX) expanded.add(seat.getPairSeatId());
    }
    if (expanded.size() > 100)
      throw error(400, "VALIDATION_ERROR", "Tối đa 100 ghế sau khi mở rộng Sweetbox");

    inventory.configureLockTimeout();
    var locked = inventory.lockSeats(showtimeId, expanded);
    Instant start =
        showtimes
            .findCurrentStartTime(showtimeId)
            .orElseThrow(() -> error(404, "SHOWTIME_NOT_FOUND", "Không tìm thấy suất chiếu"));
    Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (!now.isBefore(start)) throw error(409, "SHOWTIME_STARTED", "Suất chiếu đã bắt đầu");
    if (locked.size() != expanded.size())
      throw error(409, "SEAT_INVENTORY_INCOMPLETE", "Kho ghế suất chiếu chưa đầy đủ");

    var first = locked.getFirst();
    if (first.live(now)
        && first.getHoldToken() != null
        && owner.equals(first.getOwnerId())
        && locked.stream()
            .allMatch(
                s ->
                    s.live(now)
                        && owner.equals(s.getOwnerId())
                        && first.getHoldToken().equals(s.getHoldToken()))) {
      var members = inventory.holdMembers(first.getHoldToken());
      if (members.stream().allMatch(s -> showtimeId.equals(s.getShowtimeId()))
          && new HashSet<>(
                  members.stream().map(ShowtimeSeatRepository.HoldMember::getSeatId).toList())
              .equals(expanded)) {
        checkConsistent(locked);
        return response(locked, now);
      }
    }
    if (locked.stream().anyMatch(s -> s.getStatus() == SeatStatus.BOOKED || s.live(now)))
      throw error(409, "SEAT_NOT_AVAILABLE", "Ghế vừa được giữ hoặc bán. Vui lòng chọn lại.");
    String token = UUID.randomUUID().toString();
    locked.forEach(s -> s.hold(token, owner, now.plusSeconds(ttl), now));
    return response(locked, now);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public HoldSeatsResponse extend(
      String token, ExtendHoldRequest request, SessionPrincipal principal) {
    String owner = ownership.owner(principal);
    if (principal.role() != UserRole.STAFF)
      throw error(403, "ACCESS_DENIED", "Chỉ POS được gia hạn");
    if (request == null || !Boolean.TRUE.equals(request.waitingForTransfer()))
      throw error(400, "VALIDATION_ERROR", "Phải đang chờ chuyển khoản");
    var locked = lockToken(token);
    if (locked.isEmpty()) throw expired();
    checkOwner(locked, owner);
    checkConsistent(locked);
    Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (locked.stream().anyMatch(s -> !s.live(now))) throw expired();
    if (locked.stream().anyMatch(ShowtimeSeat::isExtensionUsed))
      throw error(409, "SEAT_HOLD_ALREADY_EXTENDED", "Chỉ được gia hạn một lần");
    if (now.isBefore(locked.getFirst().getHoldUntil().minusSeconds(ttl - after)))
      throw error(409, "SEAT_HOLD_TOO_EARLY", "Chỉ gia hạn từ phút thứ tư");
    Instant until = locked.getFirst().getHoldUntil().plusSeconds(extension);
    locked.forEach(s -> s.extend(until, now));
    return response(locked, now);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void release(String token, SessionPrincipal principal) {
    String owner = ownership.owner(principal);
    var locked = lockToken(token);
    if (locked.isEmpty()) return;
    checkOwner(locked, owner);
    Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    for (var s : locked) if (s.getStatus() == SeatStatus.HOLDING) s.release(now);
  }

  /** Scalar discovery -> ordered inventory locks -> token recheck after waiting. */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<ShowtimeSeat> lockToken(String token) {
    validateToken(token);
    var found = inventory.holdMembers(token);
    if (found.isEmpty()) return List.of();
    Long showtimeId = found.getFirst().getShowtimeId();
    if (found.stream().anyMatch(s -> !showtimeId.equals(s.getShowtimeId())))
      throw error(409, "SEAT_HOLD_INVALID", "Nhóm giữ ghế không nhất quán");
    inventory.configureLockTimeout();
    return inventory
        .lockSeats(
            showtimeId, found.stream().map(ShowtimeSeatRepository.HoldMember::getSeatId).toList())
        .stream()
        .filter(s -> token.equals(s.getHoldToken()))
        .toList();
  }

  static void validateShowtimeId(Long id) {
    if (id == null || id <= 0) throw error(400, "VALIDATION_ERROR", "ID suất chiếu phải dương");
  }

  static void validateToken(String token) {
    if (token == null
        || !token.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
      throw error(400, "VALIDATION_ERROR", "Token giữ ghế phải là UUID");
  }

  static void checkConsistent(List<ShowtimeSeat> group) {
    var first = group.getFirst();
    if (first.getHoldUntil() == null
        || group.stream()
            .anyMatch(
                s ->
                    !Objects.equals(first.getHoldUntil(), s.getHoldUntil())
                        || first.isExtensionUsed() != s.isExtensionUsed()))
      throw error(409, "SEAT_HOLD_INVALID", "Nhóm giữ ghế không nhất quán");
  }

  public static void checkOwner(List<ShowtimeSeat> group, String owner) {
    if (owner == null || group.stream().anyMatch(s -> !owner.equals(s.getOwnerId())))
      throw error(403, "SEAT_HOLD_NOT_OWNED", "Phiên này không sở hữu ghế");
  }

  public static HoldSeatsResponse response(List<ShowtimeSeat> group, Instant now) {
    var first = group.getFirst();
    return new HoldSeatsResponse(
        first.getHoldToken(),
        first.getShowtimeId(),
        group.stream().map(ShowtimeSeat::getSeatId).sorted().toList(),
        now,
        first.getHoldUntil(),
        first.isExtensionUsed());
  }

  static ApiException expired() {
    return error(409, "SEAT_HOLD_EXPIRED", "Phiên giữ ghế đã hết hạn");
  }

  private static ApiException error(int status, String code, String message) {
    return new ApiException(status, code, message);
  }
}

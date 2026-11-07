package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SeatMapService {
  private final ShowtimeRepository showtimes;
  private final SeatRepository seats;
  private final ShowtimeSeatRepository inventory;
  private final HoldOwnershipService ownership;
  private final Clock clock;

  public SeatMapService(
      ShowtimeRepository showtimes,
      SeatRepository seats,
      ShowtimeSeatRepository inventory,
      HoldOwnershipService ownership,
      Clock clock) {
    this.showtimes = showtimes;
    this.seats = seats;
    this.inventory = inventory;
    this.ownership = ownership;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public SeatMapResponse map(Long id, SessionPrincipal principal) {
    SeatHoldService.validateShowtimeId(id);
    String owner = ownership.owner(principal);
    var show =
        showtimes
            .findById(id)
            .orElseThrow(
                () -> new ApiException(404, "SHOWTIME_NOT_FOUND", "Không tìm thấy suất chiếu"));
    var physical = seats.findByAuditoriumIdOrderBySeatRowAscSeatNumberAsc(show.getAuditoriumId());
    var rows = inventory.findByShowtimeIdOrderBySeatId(id);
    Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var bySeat = new HashMap<Long, ShowtimeSeat>();
    rows.forEach(s -> bySeat.put(s.getSeatId(), s));
    // Fail closed when B/C has not initialized the complete inventory.
    if (rows.size() != physical.size()
        || physical.stream().anyMatch(s -> !bySeat.containsKey(s.getId())))
      throw new ApiException(409, "SEAT_INVENTORY_INCOMPLETE", "Kho ghế suất chiếu chưa đầy đủ");
    var views =
        physical.stream()
            .map(
                s -> {
                  var inv = bySeat.get(s.getId());
                  return new SeatMapResponse.SeatView(
                      s.getId(),
                      s.getSeatRow(),
                      s.getSeatNumber(),
                      s.getSeatType(),
                      s.getPairSeatId(),
                      inv.getStatus() == SeatStatus.HOLDING && !inv.live(now)
                          ? SeatStatus.AVAILABLE
                          : inv.getStatus(),
                      inv.live(now) && owner.equals(inv.getOwnerId()));
                })
            .toList();
    var own = new LinkedHashMap<String, List<ShowtimeSeat>>();
    rows.stream()
        .filter(s -> s.live(now) && owner.equals(s.getOwnerId()))
        .forEach(s -> own.computeIfAbsent(s.getHoldToken(), k -> new ArrayList<>()).add(s));
    return new SeatMapResponse(
        id, now, views, own.values().stream().map(g -> SeatHoldService.response(g, now)).toList());
  }
}

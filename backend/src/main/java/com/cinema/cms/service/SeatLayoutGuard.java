package com.cinema.cms.service;

import com.cinema.cms.repository.ShowtimeRepository;
import org.springframework.stereotype.Service;

@Service
public class SeatLayoutGuard {
  private final ShowtimeRepository showtimes;

  public SeatLayoutGuard(ShowtimeRepository showtimes) {
    this.showtimes = showtimes;
  }

  /** Caller must hold the Auditorium write lock, also used by Showtime CREATE. */
  public void checkMutable(Integer auditoriumId) {
    if (showtimes.existsByAuditoriumId(auditoriumId)) {
      throw new ApiException(
          409, "AUDITORIUM_LAYOUT_LOCKED", "Không thể đổi cấu trúc ghế của phòng đã có suất chiếu");
    }
  }
}

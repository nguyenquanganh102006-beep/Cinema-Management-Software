package com.cinema.cms.service;

import com.cinema.cms.repository.ShowtimeSeatRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HoldExpirationService {
  private final ShowtimeSeatRepository inventory;
  private final SeatHoldService holds;
  private final Clock clock;
  private final int batch;

  public HoldExpirationService(
      ShowtimeSeatRepository inventory,
      SeatHoldService holds,
      Clock clock,
      @Value("${app.holds.batch-size:100}") int batch) {
    this.inventory = inventory;
    this.holds = holds;
    this.clock = clock;
    this.batch = batch;
  }

  public List<String> candidates() {
    return inventory.expiredTokens(
        clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS), batch);
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public boolean expire(String token) {
    var group = holds.lockToken(token);
    var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (group.isEmpty()
        || group.stream()
            .anyMatch(
                s ->
                    s.getStatus() != com.cinema.cms.entity.SeatStatus.HOLDING
                        || s.getHoldUntil() == null
                        || now.isBefore(s.getHoldUntil()))) return false;
    group.forEach(s -> s.release(now));
    return true;
  }
}

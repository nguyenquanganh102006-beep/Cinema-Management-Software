package com.cinema.cms.service;

import com.cinema.cms.entity.Seat;
import com.cinema.cms.entity.SeatType;
import java.util.Collection;
import java.util.HashMap;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Shared, side-effect-free rules for Master Data, scheduling and seat holding. */
@Component
public class SweetboxPairValidator {
  public boolean validateCandidate(Seat first, Seat second) {
    return first != null
        && second != null
        && first.getId() != null
        && second.getId() != null
        && !first.getId().equals(second.getId())
        && Objects.equals(first.getAuditoriumId(), second.getAuditoriumId())
        && first.getSeatType() == SeatType.SWEETBOX
        && second.getSeatType() == SeatType.SWEETBOX
        && Objects.equals(first.getSeatRow(), second.getSeatRow())
        && Math.abs((long) first.getSeatNumber() - second.getSeatNumber()) == 1;
  }

  public boolean validateLayout(Collection<Seat> layout) {
    var byId = new HashMap<Long, Seat>();
    for (Seat seat : layout) {
      if (seat == null || seat.getId() == null || byId.put(seat.getId(), seat) != null)
        return false;
    }
    for (Seat seat : layout) {
      if (seat.getSeatType() == SeatType.SWEETBOX) {
        Seat pair = byId.get(seat.getPairSeatId());
        if (!validateCandidate(seat, pair) || !seat.getId().equals(pair.getPairSeatId()))
          return false;
      } else if (seat.getPairSeatId() != null) return false;
    }
    return true;
  }
}

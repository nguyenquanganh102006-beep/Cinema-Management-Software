package com.cinema.cms.service;

import static org.assertj.core.api.Assertions.*;

import com.cinema.cms.entity.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SweetboxPairValidatorTest {
  private final SweetboxPairValidator validator = new SweetboxPairValidator();

  private Seat seat(long id, int room, String row, int number, SeatType type) {
    Seat seat = new Seat(room, row, number, type);
    ReflectionTestUtils.setField(seat, "id", id);
    return seat;
  }

  @Test
  void candidateRequiresDistinctAdjacentSweetboxSeatsInSameRowAndRoom() {
    var first = seat(1, 1, "A", 1, SeatType.SWEETBOX);
    var second = seat(2, 1, "A", 2, SeatType.SWEETBOX);
    assertThat(validator.validateCandidate(first, second)).isTrue();
    for (Seat other :
        List.of(
            first,
            seat(3, 1, "A", 3, SeatType.SWEETBOX),
            seat(4, 1, "B", 2, SeatType.SWEETBOX),
            seat(5, 2, "A", 2, SeatType.SWEETBOX),
            seat(6, 1, "A", 2, SeatType.VIP))) {
      assertThat(validator.validateCandidate(first, other)).isFalse();
    }
  }

  @Test
  void layoutRequiresBothDirectionsAndRejectsPointersOnNormalSeats() {
    var first = seat(1, 1, "A", 1, SeatType.SWEETBOX);
    var second = seat(2, 1, "A", 2, SeatType.SWEETBOX);
    first.assignPair(2L);
    assertThat(validator.validateLayout(List.of(first, second))).isFalse();
    second.assignPair(1L);
    assertThat(validator.validateLayout(List.of(first, second))).isTrue();
    assertThat(validator.validateLayout(List.of(first))).isFalse();
    var normal = seat(3, 1, "B", 1, SeatType.STANDARD);
    normal.assignPair(2L);
    assertThat(validator.validateLayout(List.of(first, second, normal))).isFalse();
  }
}

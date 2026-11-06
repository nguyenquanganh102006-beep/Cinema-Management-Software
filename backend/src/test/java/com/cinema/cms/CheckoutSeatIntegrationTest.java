package com.cinema.cms;

import static org.assertj.core.api.Assertions.*;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.*;
import com.cinema.cms.support.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

class CheckoutSeatIntegrationTest extends SeatHoldTestSupport {
  @Autowired PlatformTransactionManager tx;

  @Test
  void requiresCallerTransactionAndRollsBackWithCaller() {
    var held = holds.hold(show, new HoldSeatsRequest(List.of(sweet1)), staff);
    assertThatThrownBy(
            () ->
                checkout.markBooked(held.holdToken(), ownership.owner(staff), show, held.seatIds()))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(tx)
                    .execute(
                        s -> {
                          checkout.markBooked(
                              held.holdToken(), ownership.owner(staff), show, held.seatIds());
                          throw new IllegalStateException("E failed");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(seatStatus(sweet1)).isEqualTo("HOLDING");
    assertThat(seatStatus(sweet2)).isEqualTo("HOLDING");
    new TransactionTemplate(tx)
        .executeWithoutResult(
            s ->
                checkout.markBooked(
                    held.holdToken(), ownership.owner(staff), show, held.seatIds()));
    assertThat(seatStatus(sweet1)).isEqualTo("BOOKED");
    assertThat(expiration.expire(held.holdToken())).isFalse();
  }

  @Test
  void expiredOrWrongOwnerCannotBook() {
    var held = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(tx)
                    .executeWithoutResult(
                        s ->
                            checkout.markBooked(
                                held.holdToken(),
                                ownership.owner(otherSession),
                                show,
                                held.seatIds())))
        .isInstanceOf(ApiException.class);
    clock.advance(300);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(tx)
                    .executeWithoutResult(
                        s ->
                            checkout.markBooked(
                                held.holdToken(), ownership.owner(staff), show, held.seatIds())))
        .isInstanceOf(ApiException.class);
    assertThat(seatStatus(a1)).isEqualTo("HOLDING");
  }

  @Test
  void requiresExactShowtimeAndSeatMembershipIncludingLostRows() {
    var held = holds.hold(show, new HoldSeatsRequest(List.of(a1, a2)), staff);
    for (List<Long> expected : List.of(List.of(a1), List.of(a1, a2, vip))) {
      assertThatThrownBy(
              () ->
                  new TransactionTemplate(tx)
                      .executeWithoutResult(
                          s ->
                              checkout.markBooked(
                                  held.holdToken(), ownership.owner(staff), show, expected)))
          .isInstanceOf(ApiException.class)
          .extracting(e -> ((ApiException) e).getCode())
          .isEqualTo("SEAT_HOLD_MISMATCH");
    }
    assertThatThrownBy(
            () ->
                new TransactionTemplate(tx)
                    .executeWithoutResult(
                        s ->
                            checkout.markBooked(
                                held.holdToken(),
                                ownership.owner(staff),
                                secondShow,
                                held.seatIds())))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).getCode())
        .isEqualTo("SEAT_HOLD_MISMATCH");
    jdbc.update(
        "update showtime_seats set status='AVAILABLE',hold_token=null,owner_id=null,hold_until=null"
            + " where showtime_id=? and seat_id=?",
        show,
        a2);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(tx)
                    .executeWithoutResult(
                        s ->
                            checkout.markBooked(
                                held.holdToken(), ownership.owner(staff), show, held.seatIds())))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).getCode())
        .isEqualTo("SEAT_HOLD_MISMATCH");
    assertThat(seatStatus(a1)).isEqualTo("HOLDING");
  }

  @Test
  void transitionRechecksExpiryAfterEarlierValidation() {
    var held = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(tx)
                    .executeWithoutResult(
                        s -> {
                          checkout.lockAndValidateHold(
                              held.holdToken(), ownership.owner(staff), show, held.seatIds());
                          clock.advance(300);
                          checkout.markBooked(
                              held.holdToken(), ownership.owner(staff), show, held.seatIds());
                        }))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).getCode())
        .isEqualTo("SEAT_HOLD_EXPIRED");
    assertThat(seatStatus(a1)).isEqualTo("HOLDING");
  }
}

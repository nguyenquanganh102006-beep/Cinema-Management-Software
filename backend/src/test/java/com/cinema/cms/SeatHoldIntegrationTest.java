package com.cinema.cms;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.service.*;
import com.cinema.cms.support.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SeatHoldIntegrationTest extends SeatHoldTestSupport {
  @Test
  void holdIsIdempotentAndSessionPrivate() {
    var held = holds.hold(show, new HoldSeatsRequest(List.of(a1, a2)), staff);
    clock.advance(10);
    var again = holds.hold(show, new HoldSeatsRequest(List.of(a2, a1)), staff);
    assertThat(again.holdToken()).isEqualTo(held.holdToken());
    assertThat(again.holdUntil()).isEqualTo(held.holdUntil());
    assertThat(maps.map(show, customer).ownHolds()).isEmpty();
    assertThat(maps.map(show, otherSession).ownHolds()).isEmpty();
    assertThat(maps.map(show, staff).ownHolds()).hasSize(1);
    assertThatThrownBy(() -> holds.release(held.holdToken(), otherSession))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("không sở hữu");
    holds.release(held.holdToken(), staff);
    holds.release(held.holdToken(), staff);
    assertThat(seatStatus(a1)).isEqualTo("AVAILABLE");
  }

  @Test
  void expandsSweetboxAndRejectsInvalidPairs() {
    var held = holds.hold(show, new HoldSeatsRequest(List.of(sweet1)), customer);
    assertThat(held.seatIds()).containsExactly(sweet1, sweet2);
    holds.release(held.holdToken(), customer);
    jdbc.update("update seats set pair_seat_id=null where seat_id=?", sweet2);
    assertThatThrownBy(() -> holds.hold(show, new HoldSeatsRequest(List.of(sweet1)), customer))
        .isInstanceOf(ApiException.class);
    assertThat(seatStatus(sweet1)).isEqualTo("AVAILABLE");
  }

  @Test
  void oneUnavailableSeatRollsBackWholeGroup() {
    holds.hold(show, new HoldSeatsRequest(List.of(a2)), customer);
    assertThatThrownBy(() -> holds.hold(show, new HoldSeatsRequest(List.of(a1, a2)), staff))
        .isInstanceOf(ApiException.class);
    assertThat(seatStatus(a1)).isEqualTo("AVAILABLE");
    jdbc.update(
        "update showtime_seats set status='BOOKED' where showtime_id=? and seat_id=?",
        show,
        sweet2);
    assertThatThrownBy(() -> holds.hold(show, new HoldSeatsRequest(List.of(sweet1)), staff))
        .isInstanceOf(ApiException.class);
    assertThat(seatStatus(sweet1)).isEqualTo("AVAILABLE");
  }

  @Test
  void showtimesHaveIndependentInventory() {
    holds.hold(show, new HoldSeatsRequest(List.of(a1)), customer);
    assertThat(holds.hold(secondShow, new HoldSeatsRequest(List.of(a1)), staff).showtimeId())
        .isEqualTo(secondShow);
  }

  @Test
  void extensionChecksAllBoundariesAndAddsToOldExpiry() {
    var held = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
    assertThatThrownBy(() -> holds.extend(held.holdToken(), new ExtendHoldRequest(true), staff))
        .isInstanceOf(ApiException.class);
    clock.advance(240);
    var extended = holds.extend(held.holdToken(), new ExtendHoldRequest(true), staff);
    assertThat(extended.holdUntil()).isEqualTo(held.holdUntil().plusSeconds(120));
    assertThatThrownBy(() -> holds.extend(held.holdToken(), new ExtendHoldRequest(true), staff))
        .isInstanceOf(ApiException.class);
    clock.advance(180);
    assertThatThrownBy(() -> holds.extend(held.holdToken(), new ExtendHoldRequest(true), staff))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void exactExpiryIsAvailableWithoutSchedulerAndStaleCleanupCannotDeleteNewHold() {
    var held = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
    clock.advance(300);
    assertThat(
            maps.map(show, customer).seats().stream()
                .filter(s -> s.seatId().equals(a1))
                .findFirst()
                .orElseThrow()
                .status())
        .isEqualTo(SeatStatus.AVAILABLE);
    var replacement = holds.hold(show, new HoldSeatsRequest(List.of(a1)), customer);
    assertThat(expiration.expire(held.holdToken())).isFalse();
    assertThat(seatStatus(a1)).isEqualTo("HOLDING");
    clock.advance(300);
    assertThat(expiration.expire(replacement.holdToken())).isTrue();
    assertThat(seatStatus(a1)).isEqualTo("AVAILABLE");
  }

  @Test
  void httpValidationRbacAndPrivacy() throws Exception {
    for (String seats : List.of("[]", "[0]", "[null]", "[" + a1 + "," + a1 + "]"))
      mvc.perform(
              post("/api/v1/showtimes/" + show + "/holds")
                  .header("Authorization", "Bearer " + staffJwt)
                  .contentType("application/json")
                  .content("{\"seatIds\":" + seats + "}"))
          .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/showtimes/" + show + "/seats")).andExpect(status().isUnauthorized());
    var manager =
        users.saveAndFlush(
            new User(
                "mgr_" + UUID.randomUUID().toString().substring(0, 8),
                "Manager",
                UUID.randomUUID() + "@test.invalid",
                "+86" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 10000000000L),
                "unused",
                UserRole.MANAGER));
    mvc.perform(
            get("/api/v1/showtimes/" + show + "/seats")
                .header("Authorization", "Bearer " + jwt.issue(manager)))
        .andExpect(status().isForbidden());
    var hold = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
    var response =
        mvc.perform(
                get("/api/v1/showtimes/" + show + "/seats")
                    .header("Authorization", "Bearer " + customerJwt))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain(hold.holdToken(), ownership.owner(staff), staffJwt);
    mvc.perform(
            post("/api/v1/holds/" + hold.holdToken() + "/extend")
                .header("Authorization", "Bearer " + customerJwt)
                .contentType("application/json")
                .content("{\"waitingForTransfer\":true}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/holds/" + hold.holdToken() + "/extend")
                .header("Authorization", "Bearer " + staffJwt)
                .contentType("application/json")
                .content("{\"waitingForTransfer\":false}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void startedShowCannotBeHeld() {
    clock.advance(3600);
    assertThatThrownBy(() -> holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void malformedPathIsValidationError() throws Exception {
    mvc.perform(
            get("/api/v1/showtimes/not-a-number/seats")
                .header("Authorization", "Bearer " + staffJwt))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}

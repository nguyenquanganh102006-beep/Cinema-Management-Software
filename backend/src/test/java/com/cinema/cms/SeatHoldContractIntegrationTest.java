package com.cinema.cms;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.service.*;
import com.cinema.cms.support.SeatHoldTestSupport;
import java.util.*;
import org.junit.jupiter.api.Test;

class SeatHoldContractIntegrationTest extends SeatHoldTestSupport {
  private void fails(String code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).getCode())
        .isEqualTo(code);
  }

  @Test
  void rejectsPartialRetryAndCombiningHoldsWithoutChangingDeadlines() {
    var first = holds.hold(show, new HoldSeatsRequest(List.of(a1, a2)), staff);
    fails("SEAT_NOT_AVAILABLE", () -> holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff));
    fails(
        "SEAT_NOT_AVAILABLE",
        () -> holds.hold(show, new HoldSeatsRequest(List.of(a1, a2, vip)), staff));
    assertThat(seatStatus(vip)).isEqualTo("AVAILABLE");
    assertThat(maps.map(show, staff).ownHolds()).containsExactly(first);
    holds.release(first.holdToken(), staff);
    holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
    holds.hold(show, new HoldSeatsRequest(List.of(a2)), staff);
    fails(
        "SEAT_NOT_AVAILABLE", () -> holds.hold(show, new HoldSeatsRequest(List.of(a1, a2)), staff));
  }

  @Test
  void exactTimeBoundariesAndSessionChecks() {
    var held = holds.hold(show, new HoldSeatsRequest(List.of(a1, a2)), staff);
    clock.advance(239);
    fails(
        "SEAT_HOLD_TOO_EARLY",
        () -> holds.extend(held.holdToken(), new ExtendHoldRequest(true), staff));
    clock.advance(1);
    fails(
        "SEAT_HOLD_NOT_OWNED",
        () -> holds.extend(held.holdToken(), new ExtendHoldRequest(true), otherSession));
    fails(
        "VALIDATION_ERROR",
        () -> holds.extend(held.holdToken(), new ExtendHoldRequest(false), staff));
    fails(
        "ACCESS_DENIED",
        () -> holds.extend(held.holdToken(), new ExtendHoldRequest(true), customer));
    clock.advance(59);
    var extended = holds.extend(held.holdToken(), new ExtendHoldRequest(true), staff);
    assertThat(extended.holdUntil()).isEqualTo(held.holdUntil().plusSeconds(120));
    clock.advance(121);
    fails(
        "SEAT_HOLD_EXPIRED",
        () -> holds.extend(held.holdToken(), new ExtendHoldRequest(true), staff));
    var fresh = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
    clock.advance(300);
    fails(
        "SEAT_HOLD_EXPIRED",
        () -> holds.extend(fresh.holdToken(), new ExtendHoldRequest(true), staff));
  }

  @Test
  void releaseOldTokenDoesNotReleaseReclaimedSeatsAndClearsAllMetadata() {
    var old = holds.hold(show, new HoldSeatsRequest(List.of(a1, a2)), staff);
    clock.advance(300);
    var replacement = holds.hold(show, new HoldSeatsRequest(List.of(a1)), customer);
    holds.release(old.holdToken(), staff);
    assertThat(seatStatus(a1)).isEqualTo("HOLDING");
    assertThat(seatStatus(a2)).isEqualTo("AVAILABLE");
    var cleared =
        jdbc.queryForMap(
            "select owner_id,hold_token,hold_until,extension_used,version from showtime_seats where"
                + " showtime_id=? and seat_id=?",
            show,
            a2);
    assertThat(cleared.get("owner_id")).isNull();
    assertThat(cleared.get("hold_token")).isNull();
    assertThat(cleared.get("hold_until")).isNull();
    assertThat(cleared.get("extension_used")).isEqualTo(false);
    assertThat(cleared.get("version")).isEqualTo(2);
    assertThat(maps.map(show, customer).ownHolds()).containsExactly(replacement);
  }

  @Test
  void seatMapDetectsEqualCountWrongMembershipAndGetNeverCleansExpiredRows() {
    var held = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
    clock.advance(300);
    assertThat(maps.map(show, staff).ownHolds()).isEmpty();
    assertThat(seatStatus(a1)).isEqualTo("HOLDING");
    assertThat(
            jdbc.queryForObject(
                "select hold_token from showtime_seats where showtime_id=? and seat_id=?",
                String.class,
                show,
                a1))
        .isEqualTo(held.holdToken());
    Integer otherRoom =
        jdbc.queryForObject(
            "insert into auditoriums(name,total_seats) values (?,1) returning auditorium_id",
            Integer.class,
            "Other " + UUID.randomUUID());
    Long foreign = seat(otherRoom, "Z", 1, "STANDARD");
    jdbc.update(
        "update showtime_seats set seat_id=? where showtime_id=? and seat_id=?", foreign, show, a2);
    fails("SEAT_INVENTORY_INCOMPLETE", () -> maps.map(show, staff));
    jdbc.update("delete from showtime_seats where showtime_id=? and seat_id=?", show, foreign);
    fails(
        "SEAT_INVENTORY_INCOMPLETE",
        () -> holds.hold(show, new HoldSeatsRequest(List.of(a2)), staff));
  }

  @Test
  void rejectsMissingForeignAndInvalidIds() {
    fails(
        "SHOWTIME_NOT_FOUND",
        () -> holds.hold(Long.MAX_VALUE, new HoldSeatsRequest(List.of(a1)), staff));
    fails("SHOWTIME_NOT_FOUND", () -> maps.map(Long.MAX_VALUE, staff));
    fails("VALIDATION_ERROR", () -> holds.hold(0L, new HoldSeatsRequest(List.of(a1)), staff));
    fails(
        "INVALID_SEAT",
        () -> holds.hold(show, new HoldSeatsRequest(List.of(Long.MAX_VALUE)), staff));
  }

  @Test
  void sweetboxExpansionLimitIsCheckedBeforeWriting() {
    var ids = new ArrayList<Long>();
    for (int i = 0; i < 51; i++) {
      Long first = seat(room, "X", i * 2 + 1, "SWEETBOX"),
          second = seat(room, "X", i * 2 + 2, "SWEETBOX");
      jdbc.update("update seats set pair_seat_id=? where seat_id=?", second, first);
      jdbc.update("update seats set pair_seat_id=? where seat_id=?", first, second);
      ids.add(first);
    }
    fails("VALIDATION_ERROR", () -> holds.hold(show, new HoldSeatsRequest(ids), staff));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from showtime_seats where showtime_id=? and status='HOLDING'",
                Integer.class,
                show))
        .isZero();
  }

  @Test
  void httpCreateReleaseStrictIdsAndSecretRedaction() throws Exception {
    String path = "/api/v1/showtimes/" + show + "/holds";
    for (String body :
        List.of(
            "{}",
            "null",
            "{\"seatIds\":null}",
            "{\"seatIds\":[1.9]}",
            "{\"seatIds\":[\"1\"]}",
            "{\"seatIds\":[9223372036854775808]}",
            "{\"seatIds\":[" + a1 + "],\"owner\":\"fake\"}")) {
      mvc.perform(
              post(path)
                  .header("Authorization", "Bearer " + staffJwt)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    String json =
        mvc.perform(
                post(path)
                    .header("Authorization", "Bearer " + staffJwt)
                    .contentType("application/json")
                    .content("{\"seatIds\":[" + sweet1 + "]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.seatIds.length()").value(2))
            .andExpect(jsonPath("$.data.serverTime").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = mapper.readTree(json).at("/data/holdToken").asText();
    String endpoint = "/api/v1/holds/" + token;
    mvc.perform(delete(endpoint))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.path").value("/api/v1/holds/[redacted]"));
    mvc.perform(delete(endpoint).header("Authorization", "Bearer " + customerJwt))
        .andExpect(status().isForbidden())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))));
    mvc.perform(
            post(endpoint + "/extend")
                .header("Authorization", "Bearer " + customerJwt)
                .contentType("application/json")
                .content("{\"waitingForTransfer\":true}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.path").value("/api/v1/holds/[redacted]/extend"));
    for (int i = 0; i < 2; i++)
      mvc.perform(delete(endpoint).header("Authorization", "Bearer " + staffJwt))
          .andExpect(status().isNoContent())
          .andExpect(content().string(""));
    mvc.perform(delete("/api/v1/holds/not-a-uuid").header("Authorization", "Bearer " + staffJwt))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.path").value("/api/v1/holds/[redacted]"));
    mvc.perform(get("/api/v1/showtimes/-1/seats").header("Authorization", "Bearer " + staffJwt))
        .andExpect(status().isBadRequest());
  }

  @Test
  void retryKeepsExactDeadlineAtPostgresMicrosecondPrecision() {
    clock.set(clock.instant().plusNanos(123456789));
    var held = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
    clock.advance(1);
    var again = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
    assertThat(again.holdUntil()).isEqualTo(held.holdUntil());
    assertThat(held.holdUntil().getNano() % 1000).isZero();
  }

  @Test
  void readOnlyAuditDetectsMissingExtraInventoryAndMalformedPairs() throws Exception {
    String sql =
        java.nio.file.Files.readString(
            java.nio.file.Path.of("../scripts/testing/check-showtime-inventory.sql"));
    String[] queries = sql.split(";");
    assertThat(
            jdbc.queryForList(queries[0]).stream()
                .filter(r -> show.equals(r.get("showtime_id")))
                .toList())
        .isEmpty();
    jdbc.update("delete from showtime_seats where showtime_id=? and seat_id=?", show, a1);
    assertThat(
            jdbc.queryForList(queries[0]).stream()
                .filter(r -> show.equals(r.get("showtime_id")))
                .toList())
        .hasSize(1);
    jdbc.update("update seats set pair_seat_id=null where seat_id=?", sweet2);
    assertThat(
            jdbc.queryForList(queries[1]).stream()
                .filter(r -> room.equals(r.get("auditorium_id")))
                .toList())
        .hasSize(2);
  }
}

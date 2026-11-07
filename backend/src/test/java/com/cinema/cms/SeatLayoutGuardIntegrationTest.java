package com.cinema.cms;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import com.cinema.cms.service.*;
import com.cinema.cms.support.SeatHoldTestSupport;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SeatLayoutGuardIntegrationTest extends SeatHoldTestSupport {
  @Autowired SeatService seatService;
  @Autowired SweetboxService sweetbox;
  @Autowired ShowtimeService showtimes;
  @Autowired AuditoriumRepository rooms;
  @Autowired SeatRepository seats;

  private String manager() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    return jwt.issue(
        users.saveAndFlush(
            new User(
                "guard_" + suffix,
                "Manager",
                suffix + "@guard.invalid",
                "+87" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 10000000000L),
                "unused",
                UserRole.MANAGER)));
  }

  @Test
  void allLayoutEndpointsAreLockedEvenForHistoricalShowtimes() throws Exception {
    clock.advance(20000);
    String token = manager();
    var before =
        jdbc.queryForList("select * from seats where auditorium_id=? order by seat_id", room);
    for (var request :
        List.of(
            post("/api/v1/auditoriums/" + room + "/seats")
                .content("{\"seats\":[{\"row\":\"D\",\"number\":1,\"type\":\"STANDARD\"}]}"),
            patch("/api/v1/seats/" + a1).content("{\"row\":\"D\",\"number\":1,\"type\":\"VIP\"}"),
            post("/api/v1/seats/sweetbox-pairs")
                .content("{\"seatId\":" + sweet1 + ",\"pairSeatId\":" + sweet2 + "}"),
            delete("/api/v1/seats/sweetbox-pairs/" + sweet1))) {
      mvc.perform(
              request.header("Authorization", "Bearer " + token).contentType("application/json"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("AUDITORIUM_LAYOUT_LOCKED"));
    }
    assertThat(
            jdbc.queryForList("select * from seats where auditorium_id=? order by seat_id", room))
        .isEqualTo(before);
    for (Long id : List.of(show, secondShow)) {
      assertThat(
              jdbc.queryForList(
                  "select seat_id from showtime_seats where showtime_id=? order by seat_id",
                  Long.class,
                  id))
          .containsExactlyInAnyOrderElementsOf(
              before.stream().map(s -> (Long) s.get("seat_id")).toList());
    }
    // Changing display name/status is outside the structure guard.
    mvc.perform(
            patch("/api/v1/auditoriums/" + room + "/status")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"active\":false}"))
        .andExpect(status().isOk());
  }

  @Test
  void nullSeatElementsReturn400WithoutPartialInsert() throws Exception {
    int emptyRoom =
        rooms
            .saveAndFlush(
                new Auditorium("Null " + UUID.randomUUID(), 10, AuditoriumType.STANDARD_2D))
            .getId();
    String token = manager(), valid = "{\"row\":\"A\",\"number\":1,\"type\":\"STANDARD\"}";
    for (String body :
        List.of(
            "[null]",
            "[null," + valid + "]",
            "[" + valid + ",null]",
            "[" + valid + ",null," + valid + "]")) {
      mvc.perform(
              post("/api/v1/auditoriums/" + emptyRoom + "/seats")
                  .header("Authorization", "Bearer " + token)
                  .contentType("application/json")
                  .content("{\"seats\":" + body + "}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
      assertThat(seats.countByAuditoriumId(emptyRoom)).isZero();
    }
  }

  @Test
  void layoutAndCreateShowtimeRaceAlwaysPreservesExactInventory() throws Exception {
    for (int i = 0; i < 4; i++) {
      int newRoom =
          rooms
              .saveAndFlush(
                  new Auditorium("Race " + UUID.randomUUID(), 10, AuditoriumType.STANDARD_2D))
              .getId();
      seats.saveAndFlush(new Seat(newRoom, "A", 1, SeatType.STANDARD));
      var pool = Executors.newFixedThreadPool(2);
      var start = new CountDownLatch(1);
      try {
        var layout =
            pool.submit(
                () -> {
                  start.await();
                  try {
                    seatService.create(
                        newRoom,
                        new CreateSeatsRequest(List.of(new SeatRequest("A", 2, SeatType.VIP))));
                    return "OK";
                  } catch (ApiException e) {
                    return e.getCode();
                  }
                });
        var schedule =
            pool.submit(
                () -> {
                  start.await();
                  return showtimes.create(
                      new CreateShowtimeRequest(
                          movie,
                          newRoom,
                          OffsetDateTime.ofInstant(
                              clock.instant().plusSeconds(3600), ZoneOffset.UTC)));
                });
        start.countDown();
        Long id = schedule.get(10, TimeUnit.SECONDS).id();
        assertThat(layout.get(10, TimeUnit.SECONDS)).isIn("OK", "AUDITORIUM_LAYOUT_LOCKED");
        assertThat(
                jdbc.queryForList(
                    "select seat_id from showtime_seats where showtime_id=? order by seat_id",
                    Long.class,
                    id))
            .containsExactlyElementsOf(
                jdbc.queryForList(
                    "select seat_id from seats where auditorium_id=? order by seat_id",
                    Long.class,
                    newRoom));
        maps.map(id, staff);
      } finally {
        pool.shutdownNow();
      }
    }
  }
}

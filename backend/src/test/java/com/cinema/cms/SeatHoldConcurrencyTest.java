package com.cinema.cms;

import static org.assertj.core.api.Assertions.*;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.*;
import com.cinema.cms.support.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SeatHoldConcurrencyTest extends SeatHoldTestSupport {
  private List<String> race(Callable<String> a, Callable<String> b) throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var latch = new CountDownLatch(1);
    try {
      var first =
          pool.submit(
              () -> {
                latch.await();
                return a.call();
              });
      var second =
          pool.submit(
              () -> {
                latch.await();
                return b.call();
              });
      latch.countDown();
      return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  private String attempt(Runnable action) {
    try {
      action.run();
      return "OK";
    } catch (ApiException e) {
      return e.getCode();
    }
  }

  @Test
  void posAndOnlineCompeteWithoutPartialHold() throws Exception {
    var result =
        race(
            () -> attempt(() -> holds.hold(show, new HoldSeatsRequest(List.of(a1, a2)), staff)),
            () -> attempt(() -> holds.hold(show, new HoldSeatsRequest(List.of(a1)), customer)));
    assertThat(result).containsExactlyInAnyOrder("OK", "SEAT_NOT_AVAILABLE");
    var owners =
        jdbc.queryForList(
            "select distinct owner_id from showtime_seats where showtime_id=? and status='HOLDING'",
            String.class,
            show);
    assertThat(owners).hasSize(1);
    if (owners.getFirst().equals(ownership.owner(customer)))
      assertThat(seatStatus(a2)).isEqualTo("AVAILABLE");
  }

  @Test
  void concurrentExtensionsAddOnlyOnce() throws Exception {
    var held = holds.hold(show, new HoldSeatsRequest(List.of(a1, a2)), staff);
    clock.advance(240);
    Callable<String> extend =
        () -> attempt(() -> holds.extend(held.holdToken(), new ExtendHoldRequest(true), staff));
    assertThat(race(extend, extend)).containsExactlyInAnyOrder("OK", "SEAT_HOLD_ALREADY_EXTENDED");
    assertThat(maps.map(show, staff).ownHolds().getFirst().holdUntil())
        .isEqualTo(held.holdUntil().plusSeconds(120));
  }

  @Test
  void cleanupDoesNotUndoConcurrentExtension() throws Exception {
    var held = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
    clock.advance(240);
    race(
        () -> attempt(() -> holds.extend(held.holdToken(), new ExtendHoldRequest(true), staff)),
        () -> {
          expiration.expire(held.holdToken());
          return "OK";
        });
    assertThat(seatStatus(a1)).isEqualTo("HOLDING");
  }

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.transaction.PlatformTransactionManager tx;

  @org.springframework.beans.factory.annotation.Autowired
  com.cinema.cms.repository.ShowtimeSeatRepository inventory;

  @Test
  void lockWaitHasControlledHttpConflict() throws Exception {
    var pool = Executors.newSingleThreadExecutor();
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var future =
        pool.submit(
            () ->
                new org.springframework.transaction.support.TransactionTemplate(tx)
                    .executeWithoutResult(
                        s -> {
                          inventory.lockSeats(show, List.of(a1));
                          locked.countDown();
                          try {
                            release.await(8, TimeUnit.SECONDS);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                        }));
    try {
      assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/api/v1/showtimes/" + show + "/holds")
                  .header("Authorization", "Bearer " + staffJwt)
                  .contentType("application/json")
                  .content("{\"seatIds\":[" + a1 + "]}"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isConflict())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                  .value("SEAT_UPDATE_CONFLICT"));
    } finally {
      release.countDown();
      future.get(3, TimeUnit.SECONDS);
      pool.shutdownNow();
    }
  }

  @Test
  void cleanupCannotReleaseSeatsBookedInAnotherTransaction() throws Exception {
    var held = holds.hold(show, new HoldSeatsRequest(List.of(a1, a2)), staff);
    race(
        () -> {
          new org.springframework.transaction.support.TransactionTemplate(tx)
              .executeWithoutResult(
                  s ->
                      checkout.markBooked(
                          held.holdToken(), ownership.owner(staff), show, held.seatIds()));
          return "OK";
        },
        () -> {
          expiration.expire(held.holdToken());
          return "OK";
        });
    assertThat(seatStatus(a1)).isEqualTo("BOOKED");
    assertThat(seatStatus(a2)).isEqualTo("BOOKED");
  }

  @org.springframework.beans.factory.annotation.Autowired ShowtimeService schedules;

  private void awaitInventoryWait() {
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(1))
        .until(
            () ->
                jdbc.queryForObject(
                        "select count(*) from pg_stat_activity where datname=current_database() and"
                            + " wait_event_type='Lock' and query like '%showtime_seats%'",
                        Integer.class)
                    > 0);
  }

  @Test
  void holdRereadsCommittedScheduleAfterWaitingForInventory() throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var changed = new CountDownLatch(1);
    var commit = new CountDownLatch(1);
    var update =
        pool.submit(
            () ->
                new org.springframework.transaction.support.TransactionTemplate(tx)
                    .executeWithoutResult(
                        s -> {
                          schedules.update(
                              show,
                              new UpdateShowtimeRequest(
                                  movie,
                                  java.time.OffsetDateTime.ofInstant(
                                      clock.instant().minusSeconds(60), java.time.ZoneOffset.UTC)));
                          changed.countDown();
                          try {
                            assertThat(commit.await(5, TimeUnit.SECONDS)).isTrue();
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                          }
                        }));
    try {
      assertThat(changed.await(3, TimeUnit.SECONDS)).isTrue();
      var hold =
          pool.submit(
              () -> attempt(() -> holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff)));
      awaitInventoryWait();
      commit.countDown();
      update.get(3, TimeUnit.SECONDS);
      assertThat(hold.get(3, TimeUnit.SECONDS)).isEqualTo("SHOWTIME_STARTED");
      assertThat(seatStatus(a1)).isEqualTo("AVAILABLE");
    } finally {
      commit.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void schedulePatchRechecksAvailabilityAfterWaitingForHold() throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var changed = new CountDownLatch(1);
    var commit = new CountDownLatch(1);
    var hold =
        pool.submit(
            () ->
                new org.springframework.transaction.support.TransactionTemplate(tx)
                    .executeWithoutResult(
                        s -> {
                          holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
                          changed.countDown();
                          try {
                            assertThat(commit.await(5, TimeUnit.SECONDS)).isTrue();
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                          }
                        }));
    try {
      assertThat(changed.await(3, TimeUnit.SECONDS)).isTrue();
      var update =
          pool.submit(
              () ->
                  attempt(
                      () ->
                          schedules.update(
                              show,
                              new UpdateShowtimeRequest(
                                  movie,
                                  java.time.OffsetDateTime.ofInstant(
                                      clock.instant().plusSeconds(4000),
                                      java.time.ZoneOffset.UTC)))));
      awaitInventoryWait();
      commit.countDown();
      hold.get(3, TimeUnit.SECONDS);
      assertThat(update.get(3, TimeUnit.SECONDS)).isEqualTo("SHOWTIME_CANNOT_BE_MODIFIED");
      assertThat(seatStatus(a1)).isEqualTo("HOLDING");
    } finally {
      commit.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void staleCleanupCandidateRechecksExtensionCommittedAfterDiscovery() throws Exception {
    var held = holds.hold(show, new HoldSeatsRequest(List.of(a1, a2)), staff);
    clock.advance(240);
    var pool = Executors.newFixedThreadPool(2);
    var extended = new CountDownLatch(1);
    var commit = new CountDownLatch(1);
    var extension =
        pool.submit(
            () ->
                new org.springframework.transaction.support.TransactionTemplate(tx)
                    .executeWithoutResult(
                        s -> {
                          holds.extend(held.holdToken(), new ExtendHoldRequest(true), staff);
                          extended.countDown();
                          try {
                            assertThat(commit.await(5, TimeUnit.SECONDS)).isTrue();
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                          }
                        }));
    try {
      assertThat(extended.await(3, TimeUnit.SECONDS)).isTrue();
      clock.advance(60);
      assertThat(expiration.candidates()).contains(held.holdToken());
      var cleanup = pool.submit(() -> expiration.expire(held.holdToken()));
      awaitInventoryWait();
      commit.countDown();
      extension.get(3, TimeUnit.SECONDS);
      assertThat(cleanup.get(3, TimeUnit.SECONDS)).isFalse();
      assertThat(maps.map(show, staff).ownHolds().getFirst().holdUntil())
          .isEqualTo(held.holdUntil().plusSeconds(120));
    } finally {
      commit.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void staleReleaseWaitingForReclaimCannotReleaseNewOwnersHold() throws Exception {
    var held = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
    clock.advance(300);
    var pool = Executors.newFixedThreadPool(2);
    var reclaimed = new CountDownLatch(1);
    var commit = new CountDownLatch(1);
    var reclaim =
        pool.submit(
            () ->
                new org.springframework.transaction.support.TransactionTemplate(tx)
                    .executeWithoutResult(
                        s -> {
                          holds.hold(show, new HoldSeatsRequest(List.of(a1)), customer);
                          reclaimed.countDown();
                          try {
                            assertThat(commit.await(5, TimeUnit.SECONDS)).isTrue();
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                          }
                        }));
    try {
      assertThat(reclaimed.await(3, TimeUnit.SECONDS)).isTrue();
      var release = pool.submit(() -> holds.release(held.holdToken(), staff));
      awaitInventoryWait();
      commit.countDown();
      reclaim.get(3, TimeUnit.SECONDS);
      release.get(3, TimeUnit.SECONDS);
      assertThat(maps.map(show, customer).ownHolds()).hasSize(1);
      assertThat(seatStatus(a1)).isEqualTo("HOLDING");
    } finally {
      commit.countDown();
      pool.shutdownNow();
    }
  }
}

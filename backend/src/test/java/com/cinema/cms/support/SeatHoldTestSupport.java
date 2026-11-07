package com.cinema.cms.support;

import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import com.cinema.cms.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "app.jwt.secret=seat-test-only-secret-at-least-32-bytes-long",
      "app.holds.cleanup-delay-ms=3600000",
      "springdoc.api-docs.enabled=true"
    })
@AutoConfigureMockMvc
@Import(SeatHoldTestSupport.TimeConfig.class)
public abstract class SeatHoldTestSupport {
  @TestConfiguration
  public static class TimeConfig {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  static PostgreSQLContainer<?> postgres;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    String url = System.getenv("DB_TEST_URL");
    if (url != null && !url.isBlank()) {
      properties.add("spring.datasource.url", () -> url);
      properties.add(
          "spring.datasource.username",
          () -> System.getenv().getOrDefault("DB_TEST_USERNAME", "cinema_test"));
      properties.add(
          "spring.datasource.password", () -> System.getenv().getOrDefault("DB_TEST_PASSWORD", ""));
    } else {
      if (postgres == null) {
        postgres = new PostgreSQLContainer<>("postgres:18.1");
        postgres.start();
      }
      properties.add("spring.datasource.url", postgres::getJdbcUrl);
      properties.add("spring.datasource.username", postgres::getUsername);
      properties.add("spring.datasource.password", postgres::getPassword);
    }
  }

  @Autowired protected MockMvc mvc;
  @Autowired protected ObjectMapper mapper;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected MutableClock clock;
  @Autowired protected SeatHoldService holds;
  @Autowired protected SeatMapService maps;
  @Autowired protected HoldOwnershipService ownership;
  @Autowired protected CheckoutSeatService checkout;
  @Autowired protected HoldExpirationService expiration;
  @Autowired protected UserRepository users;
  @Autowired protected JwtService jwt;
  protected Integer room;
  protected Long movie;
  protected Long show, secondShow, a1, a2, vip, sweet1, sweet2;
  protected SessionPrincipal staff, customer, otherSession;
  protected String staffJwt, customerJwt;

  @BeforeEach
  void fixture() {
    clock.set(Instant.parse("2030-01-04T10:00:00Z"));
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    var u =
        users.saveAndFlush(
            new User(
                "staff_" + suffix,
                "Staff",
                suffix + "s@test.invalid",
                "+84" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 10000000000L),
                "test-only-not-used",
                UserRole.STAFF));
    var c =
        users.saveAndFlush(
            new User(
                "cus_" + suffix,
                "Customer",
                suffix + "c@test.invalid",
                "+85" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 10000000000L),
                "test-only-not-used",
                UserRole.CUSTOMER));
    staffJwt = jwt.issue(u);
    customerJwt = jwt.issue(c);
    staff = principal(u, staffJwt);
    customer = principal(c, customerJwt);
    otherSession = principal(u, jwt.issue(u));
    movie =
        jdbc.queryForObject(
            "insert into movies(title,duration_minutes,age_rating,release_date) values"
                + " (?,120,'P','2030-01-01') returning movie_id",
            Long.class,
            "Movie " + suffix);
    room =
        jdbc.queryForObject(
            "insert into auditoriums(name,total_seats) values (?,5) returning auditorium_id",
            Integer.class,
            "Room " + suffix);
    a1 = seat(room, "A", 1, "STANDARD");
    a2 = seat(room, "A", 2, "STANDARD");
    vip = seat(room, "B", 1, "VIP");
    sweet1 = seat(room, "C", 1, "SWEETBOX");
    sweet2 = seat(room, "C", 2, "SWEETBOX");
    jdbc.update("update seats set pair_seat_id=? where seat_id=?", sweet2, sweet1);
    jdbc.update("update seats set pair_seat_id=? where seat_id=?", sweet1, sweet2);
    show = show(movie, room, 3600);
    secondShow = show(movie, room, 14400);
  }

  protected SessionPrincipal principal(User u, String token) {
    var c = jwt.parse(token);
    return new SessionPrincipal(
        u.getId(), u.getUsername(), u.getRole(), c.getId(), c.getExpiration().toInstant());
  }

  protected Long seat(Integer room, String row, int n, String type) {
    return jdbc.queryForObject(
        "insert into seats(auditorium_id,seat_row,seat_number,seat_type) values"
            + " (?,?,?,?::seat_type_enum) returning seat_id",
        Long.class,
        room,
        row,
        n,
        type);
  }

  private Long show(Long movie, Integer room, int seconds) {
    Long id =
        jdbc.queryForObject(
            "insert into showtimes(movie_id,auditorium_id,start_time,end_time,base_price) values"
                + " (?,?,?,?,70000) returning showtime_id",
            Long.class,
            movie,
            room,
            Timestamp.from(clock.instant().plusSeconds(seconds)),
            Timestamp.from(clock.instant().plusSeconds(seconds + 8100)));
    jdbc.update(
        "insert into showtime_seats(showtime_id,seat_id) select ?,seat_id from seats where"
            + " auditorium_id=?",
        id,
        room);
    return id;
  }

  protected String seatStatus(Long seat) {
    return jdbc.queryForObject(
        "select status::text from showtime_seats where showtime_id=? and seat_id=?",
        String.class,
        show,
        seat);
  }
}

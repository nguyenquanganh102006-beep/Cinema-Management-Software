package com.cinema.cms;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import com.cinema.cms.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.jwt.secret=showtime-test-only-secret-at-least-32-bytes", "app.jwt.ttl-seconds=1800",
        "springdoc.api-docs.enabled=true", "spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureMockMvc
class ShowtimeIntegrationTest {
    static PostgreSQLContainer<?> postgres;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("DB_TEST_URL");
        if (url != null && !url.isBlank()) {
            p.add("spring.datasource.url", () -> url);
            p.add("spring.datasource.username", () -> System.getenv().getOrDefault("DB_TEST_USERNAME", "cinema_test"));
            p.add("spring.datasource.password", () -> System.getenv().getOrDefault("DB_TEST_PASSWORD", ""));
        } else {
            postgres = new PostgreSQLContainer<>("postgres:18.1"); postgres.start();
            p.add("spring.datasource.url", postgres::getJdbcUrl);
            p.add("spring.datasource.username", postgres::getUsername);
            p.add("spring.datasource.password", postgres::getPassword);
        }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MovieRepository movies;
    @Autowired AuditoriumRepository rooms;
    @Autowired SeatRepository seats;
    @Autowired UserRepository users;
    @Autowired PricingRuleRepository prices;
    @Autowired ShowtimeSeatRepository inventory;
    @Autowired ShowtimeService service;
    @Autowired JwtService jwt;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired jakarta.persistence.EntityManagerFactory emf;
    String manager;
    long movie;
    int room;
    static final String START = "2026-11-01T23:30:00+07:00";

    @BeforeEach void fixture() {
        manager = token(UserRole.MANAGER);
        movie = movies.saveAndFlush(new Movie("Show " + unique(), 120, AgeRating.P, LocalDate.of(2026, 1, 1),
                null, null, null, null, null, null, null)).getId();
        room = newRoom();
    }
    String unique() { return UUID.randomUUID().toString().replace("-", ""); }
    String token(UserRole role) {
        String s = unique();
        return jwt.issue(users.saveAndFlush(new User("s_" + s, "Showtime Tester", s + "@cinema.test",
                "09" + String.format("%010d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 10000000000L)), "test-only-not-used-for-login", role)));
    }
    int newRoom() {
        int id = rooms.saveAndFlush(new Auditorium("Show " + unique(), 10, AuditoriumType.STANDARD_2D)).getId();
        seats.saveAllAndFlush(List.of(new Seat(id, "A", 1, SeatType.STANDARD), new Seat(id, "A", 2, SeatType.VIP)));
        return id;
    }
    Map<String,Object> createBody(int auditorium, String start) { return new LinkedHashMap<>(Map.of("movieId", movie, "auditoriumId", auditorium, "startTime", start)); }
    Map<String,Object> updateBody(String start) { return new LinkedHashMap<>(Map.of("movieId", movie, "startTime", start)); }
    ResultActions send(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(request);
    }
    JsonNode data(ResultActions result) throws Exception { return mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data"); }
    JsonNode create(int auditorium, String start) throws Exception {
        return data(send(post("/api/v1/showtimes"), manager, createBody(auditorium, start)).andExpect(status().isCreated()));
    }
    long create() throws Exception { return create(room, START).path("id").asLong(); }
    int count(String table, long id) { return jdbc.queryForObject("select count(*) from " + table + " where showtime_id=?", Integer.class, id); }

    @Test void createsInventoryAndRoundtripsInstantEnumsVersionAndDefaults() throws Exception {
        JsonNode show = create(room, START);
        long id = show.path("id").asLong();
        assertThat(show.path("startTime").asText()).isEqualTo(START);
        assertThat(show.path("endTime").asText()).isEqualTo("2026-11-02T01:45:00+07:00");
        assertThat(show.path("basePrice").decimalValue()).isEqualByComparingTo("70000");
        assertThat(count("showtime_seats", id)).isEqualTo(2);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var rows = inventory.findByShowtimeIdForUpdate(id);
            assertThat(rows).allSatisfy(s -> {
                assertThat(s.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
                assertThat(s.getVersion()).isZero(); assertThat(s.isExtensionUsed()).isFalse();
                assertThat(s.getOwnerId()).isNull(); assertThat(s.getHoldToken()).isNull();
                assertThat(s.getHoldUntil()).isNull(); assertThat(s.getUpdatedAt()).isNotNull();
            });
        });
        assertThat(jdbc.queryForObject("select start_time from showtimes where showtime_id=?", java.sql.Timestamp.class, id).toInstant())
                .isEqualTo(Instant.parse("2026-11-01T16:30:00Z"));
        send(get("/api/v1/showtimes/" + id), manager, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id)).andExpect(jsonPath("$.data.basePrice").value(70000));
        long seat = jdbc.queryForObject("select min(seat_id) from showtime_seats where showtime_id=?", Long.class, id);
        assertThatThrownBy(() -> jdbc.update("insert into showtime_seats(showtime_id,seat_id) values(?,?)", id, seat))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void createsWithCommonBaseForThreeDWithoutAddingRoomSurcharge() throws Exception {
        jdbc.update("update auditoriums set auditorium_type='3D' where auditorium_id=?", room);
        assertThat(create(room, START).path("basePrice").decimalValue()).isEqualByComparingTo("70000");
    }

    @Test void rejectsInvalidBodiesAndClientControlledFields() throws Exception {
        List<Map<String,Object>> invalid = new ArrayList<>();
        for (String field : List.of("movieId", "auditoriumId", "startTime")) {
            var missing = createBody(room, START); missing.remove(field); invalid.add(missing);
            var empty = createBody(room, START); empty.put(field, null); invalid.add(empty);
        }
        for (String field : List.of("endTime", "basePrice", "status", "seatIds")) {
            var body = createBody(room, START); body.put(field, "unsupported"); invalid.add(body);
        }
        for (String date : List.of("2026-11-01T12:00:00", "bad", "2026-02-30T12:00:00+07:00", "9999-12-31T23:00:00+07:00")) invalid.add(createBody(room, date));
        var negative = createBody(room, START); negative.put("movieId", -1); invalid.add(negative);
        for (String field : List.of("movieId", "auditoriumId")) {
            var fractional = createBody(room, START); fractional.put(field, 1.5); invalid.add(fractional);
            var text = createBody(room, START); text.put(field, "1"); invalid.add(text);
        }
        var numericTime = createBody(room, START); numericTime.put("startTime", 1793548800); invalid.add(numericTime);
        for (var body : invalid) send(post("/api/v1/showtimes"), manager, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("select count(*) from showtimes where auditorium_id=?", Integer.class, room)).isZero();
    }

    @Test void rejectsMissingInactiveAndEmptyMasterData() throws Exception {
        send(post("/api/v1/showtimes"), manager, createBody(Integer.MAX_VALUE, START)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AUDITORIUM_NOT_FOUND"));
        var body = createBody(room, START); body.put("movieId", Long.MAX_VALUE);
        send(post("/api/v1/showtimes"), manager, body).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"));
        jdbc.update("update movies set is_active=false where movie_id=?", movie);
        send(post("/api/v1/showtimes"), manager, createBody(room, START)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MOVIE_INACTIVE"));
        jdbc.update("update movies set is_active=true where movie_id=?", movie);
        jdbc.update("update auditoriums set is_active=false where auditorium_id=?", room);
        send(post("/api/v1/showtimes"), manager, createBody(room, START)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("AUDITORIUM_INACTIVE"));
        jdbc.update("update auditoriums set is_active=true where auditorium_id=?", room);
        jdbc.update("delete from seats where auditorium_id=?", room);
        send(post("/api/v1/showtimes"), manager, createBody(room, START)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("AUDITORIUM_HAS_NO_SEATS"));
    }

    @Test void validatesSweetboxConfigurationWithoutImplementingHolding() throws Exception {
        var physical = seats.findByAuditoriumIdOrderBySeatRowAscSeatNumberAsc(room);
        long a = physical.get(0).getId(), b = physical.get(1).getId();
        jdbc.update("update seats set seat_type='SWEETBOX' where auditorium_id=?", room);
        send(post("/api/v1/showtimes"), manager, createBody(room, START)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SWEETBOX_PAIR_INVALID"));
        jdbc.update("update seats set pair_seat_id=? where seat_id=?", b, a);
        jdbc.update("update seats set pair_seat_id=? where seat_id=?", a, b);
        jdbc.update("update seats set seat_number=4 where seat_id=?", b);
        send(post("/api/v1/showtimes"), manager, createBody(room, START)).andExpect(status().isUnprocessableEntity());
        jdbc.update("update seats set seat_number=2 where seat_id=?", b);
        long id = create(); assertThat(count("showtime_seats", id)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"2026-11-01T23:30:00+07:00", "2026-11-01T22:30:00+07:00", "2026-11-02T00:00:00+07:00"})
    void rejectsOverlapsIncludingMidnightAndBuffer(String start) throws Exception {
        create();
        send(post("/api/v1/showtimes"), manager, createBody(room, start)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOWTIME_CONFLICT"));
    }

    @Test void permitsTouchingBoundariesAndSameTimeInAnotherRoom() throws Exception {
        create(); create(room, "2026-11-01T21:15:00+07:00"); create(room, "2026-11-02T01:45:00+07:00"); create(newRoom(), START);
    }

    @Test void rollbackIncludesShowtimeAndEarlierSeatInsertsWhenARealInsertFails() {
        long failedSeat = seats.findByAuditoriumIdOrderBySeatRowAscSeatNumberAsc(room).get(1).getId();
        String name = "showtime_fail_" + unique();
        jdbc.execute("create function " + name + "() returns trigger language plpgsql as $$ begin if NEW.seat_id=" + failedSeat + " then raise exception 'test insert failure'; end if; return NEW; end $$");
        jdbc.execute("create trigger " + name + " before insert on showtime_seats for each row execute function " + name + "()");
        try {
            assertThatThrownBy(() -> service.create(new CreateShowtimeRequest(movie, room, OffsetDateTime.parse(START)))).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("select count(*) from showtimes where auditorium_id=?", Integer.class, room)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from showtime_seats ss join seats s on s.seat_id=ss.seat_id where s.auditorium_id=?", Integer.class, room)).isZero();
        } finally { jdbc.execute("drop trigger " + name + " on showtime_seats"); jdbc.execute("drop function " + name + "()"); }
    }

    @Test void patchRecalculatesTimeExcludesItselfAndPreservesMappingAndBase() throws Exception {
        long id = create();
        var before = jdbc.queryForList("select * from showtime_seats where showtime_id=? order by seat_id", id);
        long otherMovie = movies.saveAndFlush(new Movie("Other " + unique(), 90, AgeRating.P, LocalDate.of(2026,1,1), null,null,null,null,null,null,null)).getId();
        var body = updateBody("2026-11-01T23:40:00+07:00"); body.put("movieId", otherMovie);
        send(patch("/api/v1/showtimes/" + id), manager, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.movieId").value(otherMovie)).andExpect(jsonPath("$.data.endTime").value("2026-11-02T01:25:00+07:00"));
        assertThat(jdbc.queryForList("select * from showtime_seats where showtime_id=? order by seat_id", id)).isEqualTo(before);
        // Master duration changes leave stored historical times unchanged until an explicit PATCH.
        jdbc.update("update movies set duration_minutes=60 where movie_id=?", otherMovie);
        send(get("/api/v1/showtimes/" + id), manager, null).andExpect(jsonPath("$.data.endTime").value("2026-11-02T01:25:00+07:00"));
        assertThat(jdbc.queryForObject("select base_price from showtimes where showtime_id=?", java.math.BigDecimal.class, id)).isEqualByComparingTo("70000");
    }

    @Test void rejectsPatchOmissionsNullAndImmutableFieldsFollowingMasterDataConvention() throws Exception {
        long id = create();
        for (String field : List.of("movieId", "startTime")) {
            var missing = updateBody(START); missing.remove(field);
            send(patch("/api/v1/showtimes/" + id), manager, missing).andExpect(status().isBadRequest());
            var empty = updateBody(START); empty.put(field, null);
            send(patch("/api/v1/showtimes/" + id), manager, empty).andExpect(status().isBadRequest());
        }
        for (String field : List.of("auditoriumId", "basePrice", "endTime", "status")) {
            var body = updateBody(START); body.put(field, 1);
            send(patch("/api/v1/showtimes/" + id), manager, body).andExpect(status().isBadRequest());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"HOLDING", "BOOKED"})
    void patchRejectsAnyUnavailableInventory(String state) throws Exception {
        long id = create(); jdbc.update("update showtime_seats set status=cast(? as seat_status_enum) where showtime_id=?", state, id);
        send(patch("/api/v1/showtimes/" + id), manager, updateBody(START)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOWTIME_CANNOT_BE_MODIFIED"));
    }

    @ParameterizedTest @ValueSource(strings = {"VALID", "USED", "CANCELLED"})
    void patchPreservesEveryTicketIncludingCancelledHistory(String state) throws Exception {
        long id = create();
        long seat = jdbc.queryForObject("select min(showtime_seat_id) from showtime_seats where showtime_id=?", Long.class, id);
        long booking = jdbc.queryForObject("insert into bookings(booking_code,channel,total_amount,status) values(?,'POS',70000,'PAID') returning booking_id", Long.class, unique().substring(0, 30));
        jdbc.update("insert into payments(booking_id,payment_method,amount,status,paid_at) values(?,'CASH',70000,'PAID',CURRENT_TIMESTAMP)", booking);
        jdbc.update("insert into tickets(ticket_code,booking_id,showtime_seat_id,price_snapshot,status) values(?,?,?,70000,cast(? as ticket_status_enum))", unique(), booking, seat, state);
        if (!state.equals("CANCELLED")) jdbc.update("update showtime_seats set status='BOOKED' where showtime_seat_id=?", seat);
        send(patch("/api/v1/showtimes/" + id), manager, updateBody(START)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOWTIME_CANNOT_BE_MODIFIED"));
        assertThat(count("showtime_seats", id)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("delete from showtime_seats where showtime_seat_id=?", seat)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void conflictingPatchRollsBackAndMissingResourceReturnsEnvelope() throws Exception {
        long id = create(); create(room, "2026-11-02T03:00:00+07:00");
        send(patch("/api/v1/showtimes/" + id), manager, updateBody("2026-11-02T02:00:00+07:00")).andExpect(status().isConflict());
        send(get("/api/v1/showtimes/" + id), manager, null).andExpect(jsonPath("$.data.startTime").value(START));
        for (MockHttpServletRequestBuilder req : List.of(get("/api/v1/showtimes/9223372036854775807"), patch("/api/v1/showtimes/9223372036854775807"))) {
            send(req, manager, updateBody(START)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOWTIME_NOT_FOUND"))
                    .andExpect(jsonPath("$.timestamp").exists()).andExpect(jsonPath("$.path").exists()).andExpect(jsonPath("$.details").isMap());
        }
    }

    @Test void listFiltersByLocalStartDateOrdersStablyAndDoesNotLazyLoadMasterData() throws Exception {
        long first = create(), second = create(newRoom(), START).path("id").asLong();
        create(room, "2026-11-02T03:00:00+07:00");
        send(get("/api/v1/showtimes").param("movieId", "" + movie).param("date", "2026-11-01").param("size", "1"), manager, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(first)).andExpect(jsonPath("$.data.totalElements").value(2)).andExpect(jsonPath("$.data.totalPages").value(2));
        send(get("/api/v1/showtimes").param("movieId", "" + movie).param("date", "2026-11-01").param("page", "1").param("size", "1"), manager, null)
                .andExpect(jsonPath("$.data.items[0].id").value(second));
        send(get("/api/v1/showtimes").param("auditoriumId", "" + room).param("date", "2026-11-02"), manager, null)
                .andExpect(jsonPath("$.data.totalElements").value(1));
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics(); stats.clear();
        service.list("" + movie, null, null, "0", "1");
        assertThat(stats.getPrepareStatementCount()).isEqualTo(2); // bounded page + count, no movie/room lookups
    }

    @Test void validatesFiltersIdsAndOpenApiDescribesShowtime() throws Exception {
        for (var entry : Map.of("page", "2147483647", "size", "101", "movieId", "-1", "auditoriumId", "abc", "date", "2026-02-30").entrySet())
            send(get("/api/v1/showtimes").param(entry.getKey(), entry.getValue()), manager, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        for (String id : List.of("abc", "0", "-1", "999999999999999999999999")) send(get("/api/v1/showtimes/" + id), manager, null).andExpect(status().isBadRequest());
        send(get("/v3/api-docs"), null, null).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/showtimes'].post").exists());
    }

    @Test void usesFoundationAuthenticationAndManagerOnlyWrites() throws Exception {
        long id = create();
        send(get("/api/v1/showtimes"), null, null).andExpect(status().isUnauthorized());
        send(get("/api/v1/showtimes/" + id), null, null).andExpect(status().isUnauthorized());
        send(post("/api/v1/showtimes"), null, createBody(room, START)).andExpect(status().isUnauthorized());
        send(patch("/api/v1/showtimes/" + id), null, updateBody(START)).andExpect(status().isUnauthorized());
        for (UserRole role : List.of(UserRole.STAFF, UserRole.CUSTOMER)) {
            String token = token(role);
            send(get("/api/v1/showtimes"), token, null).andExpect(status().isOk());
            send(get("/api/v1/showtimes/" + id), token, null).andExpect(status().isOk());
            send(post("/api/v1/showtimes"), token, createBody(room, START)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            send(patch("/api/v1/showtimes/" + id), token, updateBody(START)).andExpect(status().isForbidden());
        }
    }

    List<Integer> race(Callable<MvcResult> a, Callable<MvcResult> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<MvcResult> left = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return a.call(); });
            Future<MvcResult> right = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return b.call(); });
            return List.of(left.get(20, TimeUnit.SECONDS).getResponse().getStatus(), right.get(20, TimeUnit.SECONDS).getResponse().getStatus());
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentOverlappingCreatesOnlyOneCommits() throws Exception {
        assertThat(race(() -> send(post("/api/v1/showtimes"), manager, createBody(room, START)).andReturn(),
                () -> send(post("/api/v1/showtimes"), manager, createBody(room, START)).andReturn())).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("select count(*) from showtimes where auditorium_id=?", Integer.class, room)).isEqualTo(1);
    }
    @Test void concurrentNonOverlappingCreatesBothCommit() throws Exception {
        assertThat(race(() -> send(post("/api/v1/showtimes"), manager, createBody(room, START)).andReturn(),
                () -> send(post("/api/v1/showtimes"), manager, createBody(room, "2026-11-02T01:45:00+07:00")).andReturn())).containsExactly(201, 201);
    }
    @Test void concurrentSameTimeInDifferentRoomsBothCommit() throws Exception {
        int other = newRoom();
        assertThat(race(() -> send(post("/api/v1/showtimes"), manager, createBody(room, START)).andReturn(),
                () -> send(post("/api/v1/showtimes"), manager, createBody(other, START)).andReturn())).containsExactly(201, 201);
    }
    @Test void concurrentCreateAndPatchCannotIntroduceOverlap() throws Exception {
        long id = create(); String target = "2026-11-02T03:00:00+07:00";
        var statuses = race(() -> send(post("/api/v1/showtimes"), manager, createBody(room, target)).andReturn(),
                () -> send(patch("/api/v1/showtimes/" + id), manager, updateBody(target)).andReturn());
        assertThat(statuses.stream().filter(s -> s == 200 || s == 201)).hasSize(1);
        assertThat(statuses).contains(409);
    }

    @Test void readsCurrentCommonBaseButNeverRepricesAnExistingShowtime() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            try {
                var first = service.create(new CreateShowtimeRequest(movie, room, OffsetDateTime.parse(START)));
                var base = prices.findByRuleTypeOrderByTargetRefAsc(PricingRuleType.BASE_PRICE).getFirst();
                base.update(new java.math.BigDecimal("80000"), base.getDescription()); prices.flush();
                var updated = service.update(first.id(), new UpdateShowtimeRequest(movie, OffsetDateTime.parse(START)));
                assertThat(updated.basePrice()).isEqualByComparingTo("70000");
                var second = service.create(new CreateShowtimeRequest(movie, room, OffsetDateTime.parse("2026-11-02T03:00:00+07:00")));
                assertThat(second.basePrice()).isEqualByComparingTo("80000");
            } finally { tx.setRollbackOnly(); }
        });
    }

    @Test void missingBaseRuleDoesNotPersistAShowtime() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            try {
                jdbc.update("delete from pricing_rules where rule_type='BASE_PRICE' and target_ref='STANDARD_2D'");
                assertThatThrownBy(() -> service.create(new CreateShowtimeRequest(movie, room, OffsetDateTime.parse(START))))
                        .isInstanceOf(ApiException.class).extracting(e -> ((ApiException)e).getCode()).isEqualTo("PRICING_RULE_MISSING");
                assertThat(jdbc.queryForObject("select count(*) from showtimes where auditorium_id=?", Integer.class, room)).isZero();
            } finally { tx.setRollbackOnly(); }
        });
    }

    @Test void concurrentPatchesOfDifferentShowtimesCannotIntroduceOverlap() throws Exception {
        long first = create(), second = create(room, "2026-11-02T03:00:00+07:00").path("id").asLong();
        String target = "2026-11-02T07:00:00+07:00";
        assertThat(race(() -> send(patch("/api/v1/showtimes/" + first), manager, updateBody(target)).andReturn(),
                () -> send(patch("/api/v1/showtimes/" + second), manager, updateBody(target)).andReturn())).containsExactlyInAnyOrder(200, 409);
    }
}

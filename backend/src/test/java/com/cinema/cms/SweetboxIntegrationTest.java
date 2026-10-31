package com.cinema.cms;

import com.cinema.cms.entity.*;
import com.cinema.cms.repository.UserRepository;
import com.cinema.cms.service.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.jwt.secret=" + SweetboxIntegrationTest.SECRET, "app.jwt.ttl-seconds=1800"})
@AutoConfigureMockMvc
class SweetboxIntegrationTest {
    static final String SECRET = "sweetbox-test-only-secret-at-least-32-bytes-";
    static final String PAIRS = "/api/v1/seats/sweetbox-pairs";
    static PostgreSQLContainer<?> postgres;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("DB_TEST_URL");
        if (url != null && !url.isBlank()) {
            properties.add("spring.datasource.url", () -> url);
            properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("DB_TEST_USERNAME", "cinema_test"));
            properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("DB_TEST_PASSWORD", ""));
        } else {
            postgres = new PostgreSQLContainer<>("postgres:18.1"); postgres.start();
            properties.add("spring.datasource.url", postgres::getJdbcUrl);
            properties.add("spring.datasource.username", postgres::getUsername);
            properties.add("spring.datasource.password", postgres::getPassword);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private String unique() { return UUID.randomUUID().toString().substring(0, 8); }
    private String tokenFor(UserRole role) {
        String s = unique();
        return jwt.issue(users.saveAndFlush(new User("w_" + s, "Sweetbox Tester", s + "@cinema.test",
                "09" + String.format("%010d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 10000000000L)), passwords.encode("TestPass123!"), role)));
    }
    private ResultActions send(MockHttpServletRequestBuilder request, String token, Object content) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (content != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(content));
        return mvc.perform(request);
    }
    private int createRoom(String manager, int totalSeats) throws Exception {
        Map<String, Object> body = Map.of("name", "Room " + unique(), "totalSeats", totalSeats, "type", "STANDARD_2D");
        String json = send(post("/api/v1/auditoriums"), manager, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data").path("id").asInt();
    }
    private Map<String, Object> seat(String row, int number, String type) { return Map.of("row", row, "number", number, "type", type); }
    private long[] createSeats(String manager, int roomId, String row, int count, String type) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) list.add(seat(row, i, type));
        String json = send(post("/api/v1/auditoriums/" + roomId + "/seats"), manager, Map.of("seats", list)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode data = mapper.readTree(json).path("data");
        long[] ids = new long[count];
        for (int i = 0; i < count; i++) ids[i] = data.get(i).path("id").asLong();
        return ids;
    }
    private ResultActions pair(String token, Object seatId, Object pairSeatId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seatId", seatId); body.put("pairSeatId", pairSeatId);
        return send(post(PAIRS), token, body);
    }
    private Long pairOf(long seatId) { return jdbc.queryForObject("select pair_seat_id from seats where seat_id=?", Long.class, seatId); }
    private int asymmetricPairs(int roomId) {
        return jdbc.queryForObject("select count(*) from seats s where s.auditorium_id=? and s.pair_seat_id is not null and not exists "
                + "(select 1 from seats p where p.seat_id=s.pair_seat_id and p.pair_seat_id=s.seat_id)", Integer.class, roomId);
    }

    @Test void pairsSeatsInBothDirectionsAndRejectsRepeats() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int room = createRoom(manager, 20);
        long[] s = createSeats(manager, room, "A", 2, "SWEETBOX");
        pair(manager, s[0], s[1]).andExpect(status().isCreated()).andExpect(jsonPath("$.data.seats.length()").value(2))
                .andExpect(jsonPath("$.data.seats[0].pairSeatId").value(s[1])).andExpect(jsonPath("$.data.seats[1].pairSeatId").value(s[0]));
        assertThat(pairOf(s[0])).isEqualTo(s[1]);
        assertThat(pairOf(s[1])).isEqualTo(s[0]);
        assertThat(asymmetricPairs(room)).isZero();
        send(get("/api/v1/auditoriums/" + room + "/seats"), manager, null).andExpect(jsonPath("$.data[0].pairSeatId").value(s[1]));
        pair(manager, s[0], s[1]).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SWEETBOX_ALREADY_PAIRED"));
        pair(manager, s[1], s[0]).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SWEETBOX_ALREADY_PAIRED"));
    }

    @Test void rejectsInvalidPairs() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int room = createRoom(manager, 20), otherRoom = createRoom(manager, 20);
        long[] sweet = createSeats(manager, room, "A", 2, "SWEETBOX"), standard = createSeats(manager, room, "B", 1, "STANDARD"), foreign = createSeats(manager, otherRoom, "A", 1, "SWEETBOX");
        pair(manager, sweet[0], sweet[0]).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SWEETBOX_PAIR_INVALID")); 
        pair(manager, sweet[0], foreign[0]).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SWEETBOX_PAIR_INVALID"));
        pair(manager, sweet[0], standard[0]).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SWEETBOX_PAIR_INVALID"));
        pair(manager, standard[0], sweet[0]).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SWEETBOX_PAIR_INVALID"));
        pair(manager, sweet[0], 999999999L).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"));
        pair(manager, 999999999L, sweet[0]).andExpect(status().isNotFound());
        pair(manager, null, sweet[1]).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        pair(manager, sweet[0], 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        pair(manager, -1, sweet[1]).andExpect(status().isBadRequest());
        send(post(PAIRS), manager, Map.of("seatId", sweet[0], "pairSeatId", sweet[1], "force", true)).andExpect(status().isBadRequest());
        send(post(PAIRS), manager, Map.of()).andExpect(status().isBadRequest());
        for (long id : new long[]{sweet[0], sweet[1], standard[0], foreign[0]}) assertThat(pairOf(id)).isNull();
    }

    @Test void rejectsConflictingAndOneWayPairs() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int room = createRoom(manager, 20);
        long[] a = createSeats(manager, room, "A", 4, "SWEETBOX");
        pair(manager, a[0], a[1]).andExpect(status().isCreated());
        pair(manager, a[0], a[2]).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SWEETBOX_ALREADY_PAIRED"));
        pair(manager, a[2], a[1]).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SWEETBOX_ALREADY_PAIRED"));
        pair(manager, a[2], a[3]).andExpect(status().isCreated());
        assertThat(asymmetricPairs(room)).isZero();

        long[] b = createSeats(manager, room, "B", 3, "SWEETBOX");
        jdbc.update("update seats set pair_seat_id=? where seat_id=?", b[1], b[0]);
        pair(manager, b[0], b[2]).andExpect(status().isConflict());
        pair(manager, b[1], b[2]).andExpect(status().isConflict()); 
        assertThat(pairOf(b[2])).isNull();
    }

    @Test void unpairClearsBothSidesFromEitherSeat() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int room = createRoom(manager, 20);
        long[] a = createSeats(manager, room, "A", 2, "SWEETBOX");
        pair(manager, a[0], a[1]).andExpect(status().isCreated());
        send(delete(PAIRS + "/" + a[0]), manager, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.seats.length()").value(2)).andExpect(jsonPath("$.data.seats[0].pairSeatId").isEmpty());
        assertThat(pairOf(a[0])).isNull();
        assertThat(pairOf(a[1])).isNull();
        send(delete(PAIRS + "/" + a[0]), manager, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SWEETBOX_PAIR_NOT_FOUND"));
        pair(manager, a[0], a[1]).andExpect(status().isCreated());
        send(delete(PAIRS + "/" + a[1]), manager, null).andExpect(status().isOk());
        assertThat(pairOf(a[0])).isNull();

        long[] b = createSeats(manager, room, "B", 2, "SWEETBOX");
        jdbc.update("update seats set pair_seat_id=? where seat_id=?", b[1], b[0]);
        send(delete(PAIRS + "/" + b[1]), manager, null).andExpect(status().isOk());
        assertThat(pairOf(b[0])).isNull();
        send(delete(PAIRS + "/999999999"), manager, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"));
    }

    @Test void pairedSeatsCannotBeEditedUntilUnpaired() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int room = createRoom(manager, 20);
        long[] a = createSeats(manager, room, "A", 2, "SWEETBOX");
        pair(manager, a[0], a[1]).andExpect(status().isCreated());
        send(patch("/api/v1/seats/" + a[0]), manager, seat("A", 1, "STANDARD")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEAT_IS_PAIRED"));
        send(delete(PAIRS + "/" + a[0]), manager, null).andExpect(status().isOk());
        send(patch("/api/v1/seats/" + a[0]), manager, seat("A", 1, "STANDARD")).andExpect(status().isOk());
        pair(manager, a[0], a[1]).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SWEETBOX_PAIR_INVALID"));
    }

    @Test void databaseRejectsSelfPairsAndSharedTargets() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int room = createRoom(manager, 20);
        long[] a = createSeats(manager, room, "A", 3, "SWEETBOX");
        assertThatThrownBy(() -> jdbc.update("update seats set pair_seat_id=seat_id where seat_id=?", a[0])).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update seats set pair_seat_id=? where seat_id=?", a[2], a[0]);
        assertThatThrownBy(() -> jdbc.update("update seats set pair_seat_id=? where seat_id=?", a[2], a[1])).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void concurrentPairingsSharingASeatLeaveExactlyOneSymmetricPair() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int room = createRoom(manager, 20);
        for (String row : List.of("C", "D", "E", "F")) {
            long[] s = createSeats(manager, room, row, 3, "SWEETBOX");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CyclicBarrier start = new CyclicBarrier(2);
            try {
                Future<MvcResult> first = pool.submit(() -> { start.await(); return pair(manager, s[0], s[1]).andReturn(); });
                Future<MvcResult> second = pool.submit(() -> { start.await(); return pair(manager, s[0], s[2]).andReturn(); });
                List<MvcResult> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
                assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList()).containsExactly(201, 409);
                MvcResult loser = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
                assertThat(mapper.readTree(loser.getResponse().getContentAsString()).path("code").asText()).isEqualTo("SWEETBOX_ALREADY_PAIRED");
            } finally { pool.shutdownNow(); }
            assertThat(asymmetricPairs(room)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from seats where seat_id in (?,?,?) and pair_seat_id is not null", Integer.class, s[0], s[1], s[2])).isEqualTo(2);
        }
    }

    @Test void onlyManagerCanPairAndUnpair() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int room = createRoom(manager, 20);
        long[] a = createSeats(manager, room, "A", 2, "SWEETBOX");
        pair(null, a[0], a[1]).andExpect(status().isUnauthorized());
        send(delete(PAIRS + "/" + a[0]), null, null).andExpect(status().isUnauthorized());
        for (UserRole role : List.of(UserRole.STAFF, UserRole.CUSTOMER)) {
            String token = tokenFor(role);
            pair(token, a[0], a[1]).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            send(delete(PAIRS + "/" + a[0]), token, null).andExpect(status().isForbidden());
        }
        assertThat(pairOf(a[0])).isNull();
    }
}
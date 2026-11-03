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

@SpringBootTest(properties = {"app.jwt.secret=" + AuditoriumSeatIntegrationTest.SECRET, "app.jwt.ttl-seconds=1800"})
@AutoConfigureMockMvc
class AuditoriumSeatIntegrationTest {
    static final String SECRET = "auditorium-test-only-secret-at-least-32-bytes";
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

    // ---------- helpers ----------
    private String unique() { return UUID.randomUUID().toString().substring(0, 8); }
    private String tokenFor(UserRole role) {
        String s = unique();
        return jwt.issue(users.saveAndFlush(new User("a_" + s, "Room Tester", s + "@cinema.test",
                "09" + String.format("%010d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 10000000000L)), passwords.encode("TestPass123!"), role)));
    }
    private Map<String, Object> room(String name, int totalSeats, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("totalSeats", totalSeats); m.put("type", type);
        return m;
    }
    private Map<String, Object> seat(String row, Object number, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("row", row); m.put("number", number); if (type != null) m.put("type", type);
        return m;
    }
    private Map<String, Object> seats(Map<String, Object>... items) { return Map.of("seats", List.of(items)); }
    private ResultActions send(MockHttpServletRequestBuilder request, String token, Object content) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (content != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(content));
        return mvc.perform(request);
    }
    private int createRoom(String manager, String name, int totalSeats, String type) throws Exception {
        String json = send(post("/api/v1/auditoriums"), manager, room(name, totalSeats, type)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data").path("id").asInt();
    }
    @SafeVarargs private JsonNode createSeats(String manager, int roomId, Map<String, Object>... items) throws Exception {
        String json = send(post("/api/v1/auditoriums/" + roomId + "/seats"), manager, seats(items)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data");
    }
    private int seatCount(int roomId) { return jdbc.queryForObject("select count(*) from seats where auditorium_id=?", Integer.class, roomId); }

    // ---------- phòng chiếu ----------
    @Test void createsAuditoriumKeepsThreeDValueAndRejectsDuplicateNames() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), name = "Room " + unique();
        String json = send(post("/api/v1/auditoriums"), manager, room("  " + name + " ", 120, "3D")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value(name)).andExpect(jsonPath("$.data.type").value("3D"))
                .andExpect(jsonPath("$.data.active").value(true)).andExpect(jsonPath("$.data.seatCount").value(0))
                .andReturn().getResponse().getContentAsString();
        int id = mapper.readTree(json).path("data").path("id").asInt();
        assertThat(jdbc.queryForObject("select auditorium_type::text from auditoriums where auditorium_id=?", String.class, id)).isEqualTo("3D");
        send(get("/api/v1/auditoriums/" + id), manager, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.type").value("3D"));
        send(post("/api/v1/auditoriums"), manager, room(name.toUpperCase(), 50, "IMAX")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUDITORIUM_NAME_EXISTS"));
        send(get("/api/v1/auditoriums/999999"), manager, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AUDITORIUM_NOT_FOUND"));
    }

    @Test void rejectsInvalidAuditoriumInput() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        List<Map<String, Object>> invalid = new ArrayList<>();
        Map<String, Object> m;
        invalid.add(room(" ", 10, "IMAX"));
        invalid.add(room("x".repeat(51), 10, "IMAX"));
        invalid.add(room("A " + unique(), 0, "IMAX"));
        invalid.add(room("A " + unique(), -3, "IMAX"));
        invalid.add(room("A " + unique(), 10, "4D"));
        invalid.add(room("A " + unique(), 10, "3d"));              // phải đúng "3D"
        m = room("A " + unique(), 10, "IMAX"); m.remove("type"); invalid.add(m);
        m = room("A " + unique(), 10, "IMAX"); m.put("active", false); invalid.add(m);   // trường lạ
        for (Map<String, Object> body : invalid) send(post("/api/v1/auditoriums"), manager, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test void updatesAuditoriumProtectsCapacityAndFiltersByStatus() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), name = "Room " + unique(), other = "Room " + unique();
        int id = createRoom(manager, name, 5, "STANDARD_2D");
        createRoom(manager, other, 5, "IMAX");
        createSeats(manager, id, seat("A", 1, null), seat("A", 2, null), seat("A", 3, null));
        send(patch("/api/v1/auditoriums/" + id), manager, room(name, 2, "STANDARD_2D")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUDITORIUM_CAPACITY_BELOW_SEATS"));
        send(patch("/api/v1/auditoriums/" + id), manager, room(other, 5, "IMAX")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUDITORIUM_NAME_EXISTS"));
        send(patch("/api/v1/auditoriums/" + id), manager, room(name, 3, "IMAX")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalSeats").value(3)).andExpect(jsonPath("$.data.type").value("IMAX")).andExpect(jsonPath("$.data.seatCount").value(3));
        send(patch("/api/v1/auditoriums/999999"), manager, room("Z", 3, "IMAX")).andExpect(status().isNotFound());
        send(patch("/api/v1/auditoriums/" + id + "/status"), manager, Map.of("active", false)).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));
        send(get("/api/v1/auditoriums").param("active", "false"), manager, null).andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.name=='" + name + "')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.name=='" + other + "')]").isEmpty());
        send(get("/api/v1/auditoriums").param("active", "true"), manager, null).andExpect(jsonPath("$.data[?(@.name=='" + other + "')]").isNotEmpty());
        send(get("/api/v1/auditoriums"), manager, null).andExpect(jsonPath("$.data[?(@.name=='" + name + "')].seatCount").value(3));
        send(patch("/api/v1/auditoriums/" + id + "/status"), manager, Map.of()).andExpect(status().isBadRequest());
    }

    // ---------- ghế ----------
    @Test void createsSeatsInBatchNormalizesRowsAndIsAllOrNothing() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int id = createRoom(manager, "Room " + unique(), 5, "STANDARD_2D");
        JsonNode created = createSeats(manager, id, seat("b", 2, "VIP"), seat("a", 1, null), seat("A", 2, "SWEETBOX"));
        assertThat(created).hasSize(3);
        assertThat(created.get(0).path("row").asText() + created.get(0).path("number").asInt()).isEqualTo("A1");   // sắp xếp theo hàng rồi số
        assertThat(created.get(0).path("type").asText()).isEqualTo("STANDARD");                                      // mặc định
        assertThat(created.get(0).path("pairSeatId").isNull()).isTrue();
        send(get("/api/v1/auditoriums/" + id + "/seats"), manager, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3)).andExpect(jsonPath("$.data[2].row").value("B"));

        // một ghế trùng vị trí thì cả lô bị từ chối, ghế hợp lệ cùng lô không được lưu
        send(post("/api/v1/auditoriums/" + id + "/seats"), manager, seats(seat("C", 1, null), seat("A", 1, "VIP"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEAT_POSITION_TAKEN"));
        assertThat(seatCount(id)).isEqualTo(3);
        // vượt sức chứa (5): đang có 3, thêm 3 là 6
        send(post("/api/v1/auditoriums/" + id + "/seats"), manager, seats(seat("D", 1, null), seat("D", 2, null), seat("D", 3, null))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUDITORIUM_CAPACITY_EXCEEDED"));
        assertThat(seatCount(id)).isEqualTo(3);
        createSeats(manager, id, seat("D", 1, null), seat("D", 2, null));
        assertThat(seatCount(id)).isEqualTo(5);
        send(post("/api/v1/auditoriums/999999/seats"), manager, seats(seat("A", 1, null))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AUDITORIUM_NOT_FOUND"));
        send(get("/api/v1/auditoriums/999999/seats"), manager, null).andExpect(status().isNotFound());
    }

    @Test void rejectsInvalidSeatInput() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int id = createRoom(manager, "Room " + unique(), 600, "STANDARD_2D");
        String url = "/api/v1/auditoriums/" + id + "/seats";
        List<Object> invalid = new ArrayList<>();
        invalid.add(seats(seat("A", 0, null)));                      // số ghế phải dương
        invalid.add(seats(seat("A", -1, null)));
        invalid.add(seats(seat("A", null, null)));
        invalid.add(seats(seat("", 1, null)));
        invalid.add(seats(seat("AB1", 1, null)));                    // hàng chỉ gồm chữ cái
        invalid.add(seats(seat("ABCDEF", 1, null)));                 // tối đa 5 ký tự
        invalid.add(seats(seat("A", 1, "GOLD")));                    // loại ghế lạ
        invalid.add(seats(seat("A", 1, null), seat("a", 1, "VIP"))); // lặp trong cùng yêu cầu
        invalid.add(Map.of("seats", List.of()));                     // danh sách rỗng
        invalid.add(Map.of());                                       // thiếu danh sách
        Map<String, Object> withPair = seat("A", 1, null); withPair.put("pairSeatId", 5);
        invalid.add(seats(withPair));                                // không được gán cặp ở API này
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 1; i <= 501; i++) tooMany.add(seat("A", i, null));
        invalid.add(Map.of("seats", tooMany));                       // vượt 500 ghế mỗi lần
        for (Object body : invalid) send(post(url), manager, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(seatCount(id)).isZero();
    }

    @Test void updatesSeatsAndProtectsPairedOnes() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int id = createRoom(manager, "Room " + unique(), 10, "STANDARD_2D");
        JsonNode created = createSeats(manager, id, seat("A", 1, null), seat("A", 2, "SWEETBOX"), seat("A", 3, "SWEETBOX"));
        long a1 = created.get(0).path("id").asLong(), a2 = created.get(1).path("id").asLong(), a3 = created.get(2).path("id").asLong();
        send(patch("/api/v1/seats/" + a1), manager, seat("b", 9, "VIP")).andExpect(status().isOk()).andExpect(jsonPath("$.data.row").value("B"))
                .andExpect(jsonPath("$.data.number").value(9)).andExpect(jsonPath("$.data.type").value("VIP"));
        send(patch("/api/v1/seats/" + a1), manager, seat("A", 2, "VIP")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEAT_POSITION_TAKEN"));
        send(patch("/api/v1/seats/" + a1), manager, seat("B", 9, "VIP")).andExpect(status().isOk());                    // không đổi gì thì không bị coi là trùng
        send(patch("/api/v1/seats/" + a1), manager, seat("A", 0, "VIP")).andExpect(status().isBadRequest());
        send(patch("/api/v1/seats/999999999"), manager, seat("A", 1, null)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"));

        // API ghép cặp thuộc nhóm Sweetbox, ở đây ghép trực tiếp trong DB để kiểm tra việc bảo vệ ghế đã ghép
        jdbc.update("update seats set pair_seat_id=? where seat_id=?", a3, a2);
        jdbc.update("update seats set pair_seat_id=? where seat_id=?", a2, a3);
        send(patch("/api/v1/seats/" + a2), manager, seat("A", 2, "STANDARD")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEAT_IS_PAIRED"));
        send(patch("/api/v1/seats/" + a3), manager, seat("C", 3, "SWEETBOX")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEAT_IS_PAIRED"));
        send(patch("/api/v1/seats/" + a2), manager, seat("A", 2, "SWEETBOX")).andExpect(status().isOk()).andExpect(jsonPath("$.data.pairSeatId").value(a3));   // không đổi gì thì cho qua
    }

    // ---------- ràng buộc ở tầng database ----------
    @Test void databaseEnforcesSeatRulesAndCascadesOnDelete() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int id = createRoom(manager, "Room " + unique(), 10, "IMAX");
        createSeats(manager, id, seat("A", 1, null), seat("A", 2, null));
        assertThatThrownBy(() -> jdbc.update("insert into seats(auditorium_id,seat_row,seat_number,seat_type) values(?,?,?,'STANDARD')", id, "A", 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into seats(auditorium_id,seat_row,seat_number,seat_type) values(?,?,?,'STANDARD')", id, "Z", 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into auditoriums(name,total_seats,auditorium_type) values(?,0,'IMAX')", "Zero " + unique())).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("delete from auditoriums where auditorium_id=?", id);
        assertThat(seatCount(id)).isZero();
    }

    // ---------- phân quyền ----------
    @Test void onlyManagerCanUseAuditoriumAndSeatApis() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int id = createRoom(manager, "Room " + unique(), 10, "IMAX");
        long seatId = createSeats(manager, id, seat("A", 1, null)).get(0).path("id").asLong();
        send(get("/api/v1/auditoriums"), null, null).andExpect(status().isUnauthorized());
        send(post("/api/v1/auditoriums/" + id + "/seats"), null, seats(seat("B", 1, null))).andExpect(status().isUnauthorized());
        for (UserRole role : List.of(UserRole.STAFF, UserRole.CUSTOMER)) {
            String token = tokenFor(role);
            send(get("/api/v1/auditoriums"), token, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            send(get("/api/v1/auditoriums/" + id), token, null).andExpect(status().isForbidden());
            send(post("/api/v1/auditoriums"), token, room("Denied " + unique(), 10, "IMAX")).andExpect(status().isForbidden());
            send(patch("/api/v1/auditoriums/" + id), token, room("Denied", 10, "IMAX")).andExpect(status().isForbidden());
            send(patch("/api/v1/auditoriums/" + id + "/status"), token, Map.of("active", false)).andExpect(status().isForbidden());
            send(get("/api/v1/auditoriums/" + id + "/seats"), token, null).andExpect(status().isForbidden());
            send(post("/api/v1/auditoriums/" + id + "/seats"), token, seats(seat("B", 1, null))).andExpect(status().isForbidden());
            send(patch("/api/v1/seats/" + seatId), token, seat("A", 1, "VIP")).andExpect(status().isForbidden());
        }
        assertThat(seatCount(id)).isEqualTo(1);
    }

    // ---------- tên phòng không phân biệt hoa thường, bảo vệ ở tầng database ----------
    @Test void databaseRejectsAuditoriumNamesDifferingOnlyByCase() {
        String name = "Ci " + unique();
        jdbc.update("insert into auditoriums(name,total_seats,auditorium_type) values(?,10,'IMAX')", name);
        assertThatThrownBy(() -> jdbc.update("insert into auditoriums(name,total_seats,auditorium_type) values(?,10,'IMAX')", name.toUpperCase()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- đồng thời ----------
    private List<MvcResult> concurrently(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<MvcResult> a = pool.submit(() -> { start.await(); return first.call(); });
            Future<MvcResult> b = pool.submit(() -> { start.await(); return second.call(); });
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    private String code(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString()).path("code").asText(); }

    @Test void concurrentCreatesWithTheSameNameGiveOneSuccessAndABusinessError() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        for (int round = 0; round < 5; round++) {
            String name = "Race " + unique(), variant = round % 2 == 0 ? name : name.toUpperCase();   // vòng lẻ khác hoa thường
            List<MvcResult> results = concurrently(
                    () -> send(post("/api/v1/auditoriums"), manager, room(name, 10, "IMAX")).andReturn(),
                    () -> send(post("/api/v1/auditoriums"), manager, room(variant, 10, "IMAX")).andReturn());
            List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
            assertThat(statuses).containsExactly(201, 409);
            MvcResult loser = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertThat(code(loser)).isEqualTo("AUDITORIUM_NAME_EXISTS");   // không phải RESOURCE_CONFLICT chung
            assertThat(jdbc.queryForObject("select count(*) from auditoriums where lower(name)=lower(?)", Integer.class, name)).isEqualTo(1);
        }
    }

    @Test void concurrentSeatCreateAndMoveToTheSamePositionNeverEndInAGenericConflict() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int roomId = createRoom(manager, "Room " + unique(), 20, "STANDARD_2D");
        long movingSeat = createSeats(manager, roomId, seat("A", 1, null)).get(0).path("id").asLong();
        for (int i = 1; i <= 5; i++) {
            int number = i;
            List<MvcResult> results = concurrently(
                    () -> send(post("/api/v1/auditoriums/" + roomId + "/seats"), manager, seats(seat("B", number, null))).andReturn(),
                    () -> send(patch("/api/v1/seats/" + movingSeat), manager, seat("B", number, null)).andReturn());
            List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).toList();
            assertThat(statuses.stream().filter(s -> s == 200 || s == 201)).hasSize(1);   // đúng một bên thắng
            MvcResult loser = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertThat(code(loser)).isEqualTo("SEAT_POSITION_TAKEN");
            assertThat(jdbc.queryForObject("select count(*) from seats where auditorium_id=? and seat_row='B' and seat_number=?", Integer.class, roomId, number)).isEqualTo(1);
        }
    }
}
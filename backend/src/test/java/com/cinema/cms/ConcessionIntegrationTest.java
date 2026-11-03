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

@SpringBootTest(properties = {"app.jwt.secret=" + ConcessionIntegrationTest.SECRET, "app.jwt.ttl-seconds=1800"})
@AutoConfigureMockMvc
class ConcessionIntegrationTest {
    static final String SECRET = "concession-test-only-secret-at-least-32-bytes-";
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
    private User newUser(UserRole role) {
        String s = unique();
        return users.saveAndFlush(new User("f_" + s, "Tester " + s, s + "@cinema.test",
                "09" + String.format("%010d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 10000000000L)), passwords.encode("TestPass123!"), role));
    }
    private String tokenFor(UserRole role) { return jwt.issue(newUser(role)); }
    private ResultActions send(MockHttpServletRequestBuilder request, String token, Object content) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (content != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(content));
        return mvc.perform(request);
    }
    private int createItem(String manager, String name, Object price) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("name", name); body.put("price", price);
        String json = send(post("/api/v1/concessions"), manager, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data").path("id").asInt();
    }
    private Map<String, Object> comp(int itemId, int quantity) { return Map.of("itemId", itemId, "quantity", quantity); }
    private Map<String, Object> comboBody(String name, Object price, Object... components) {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("name", name); m.put("price", price); m.put("components", List.of(components));
        return m;
    }
    private int createCombo(String manager, String name, Object... components) throws Exception {
        String json = send(post("/api/v1/concessions/combos"), manager, comboBody(name, 99000, components)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data").path("id").asInt();
    }
    private List<MvcResult> concurrently(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<MvcResult> a = pool.submit(() -> { start.await(); return first.call(); });
            Future<MvcResult> b = pool.submit(() -> { start.await(); return second.call(); });
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    private int stockOf(int itemId) { return jdbc.queryForObject("select stock_quantity from concession_inventory where item_id=?", Integer.class, itemId); }
    private int movementCount(int itemId) { return jdbc.queryForObject("select count(*) from inventory_transactions where item_id=?", Integer.class, itemId); }

    @Test void createsItemWithItsInventoryRowAndRejectsDuplicateNames() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), name = "Bắp " + unique();
        String json = send(post("/api/v1/concessions"), manager, Map.of("name", "  " + name + " ", "price", 55000)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value(name)).andExpect(jsonPath("$.data.combo").value(false)).andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.components.length()").value(0)).andReturn().getResponse().getContentAsString();
        int id = mapper.readTree(json).path("data").path("id").asInt();
        assertThat(jdbc.queryForObject("select stock_quantity from concession_inventory where item_id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("select low_stock_threshold from concession_inventory where item_id=?", Integer.class, id)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select price from concession_items where item_id=?", java.math.BigDecimal.class, id)).isEqualByComparingTo("55000");
        send(post("/api/v1/concessions"), manager, Map.of("name", name.toUpperCase(), "price", 1)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCESSION_NAME_EXISTS"));
        send(get("/api/v1/concessions/" + id), manager, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        send(get("/api/v1/concessions/999999"), manager, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONCESSION_ITEM_NOT_FOUND"));
    }

    @Test void rejectsInvalidItemInput() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        List<Object> invalid = new ArrayList<>();
        invalid.add(Map.of("name", " ", "price", 10));
        invalid.add(Map.of("name", "x".repeat(101), "price", 10));
        invalid.add(Map.of("name", "A " + unique(), "price", -1));
        invalid.add(Map.of("name", "A " + unique(), "price", 1.234));
        invalid.add(Map.of("name", "A " + unique(), "price", 100000000));
        invalid.add(Map.of("name", "A " + unique()));
        invalid.add(Map.of("name", "A " + unique(), "price", 10, "combo", true));
        invalid.add(Map.of("name", "A " + unique(), "price", 10, "active", false));
        for (Object body : invalid) send(post("/api/v1/concessions"), manager, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test void updatesItemsButNotCombosThroughTheItemEndpoint() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        int a = createItem(manager, "Nước " + key, 30000), b = createItem(manager, "Snack " + key, 20000), combo = createCombo(manager, "Combo " + key, comp(a, 1));
        send(patch("/api/v1/concessions/" + a), manager, Map.of("name", " Nước mới " + key + " ", "price", 32000.5)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Nước mới " + key));
        assertThat(jdbc.queryForObject("select price from concession_items where item_id=?", java.math.BigDecimal.class, a)).isEqualByComparingTo("32000.50");
        send(patch("/api/v1/concessions/" + a), manager, Map.of("name", ("Snack " + key).toUpperCase(), "price", 1)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCESSION_NAME_EXISTS"));
        send(patch("/api/v1/concessions/" + a), manager, Map.of("name", "Nước mới " + key, "price", 1)).andExpect(status().isOk());
        send(patch("/api/v1/concessions/" + combo), manager, Map.of("name", "X " + key, "price", 1)).andExpect(status().isNotFound());
        send(patch("/api/v1/concessions/999999"), manager, Map.of("name", "X", "price", 1)).andExpect(status().isNotFound());
        assertThat(b).isPositive();
    }

    @Test void createsComboWithComponentsAndNoInventoryRow() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        int popcorn = createItem(manager, "Bắp " + key, 55000), drink = createItem(manager, "Nước " + key, 30000);
        String json = send(post("/api/v1/concessions/combos"), manager, comboBody("Combo đôi " + key, 99000, comp(popcorn, 1), comp(drink, 2))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.combo").value(true)).andExpect(jsonPath("$.data.components.length()").value(2))
                .andExpect(jsonPath("$.data.components[?(@.itemId==" + drink + ")].quantity").value(2))
                .andExpect(jsonPath("$.data.components[?(@.itemId==" + popcorn + ")].name").value("Bắp " + key)).andReturn().getResponse().getContentAsString();
        int combo = mapper.readTree(json).path("data").path("id").asInt();
        assertThat(jdbc.queryForObject("select count(*) from combo_items where combo_id=?", Integer.class, combo)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select is_combo from concession_items where item_id=?", Boolean.class, combo)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from concession_inventory where item_id=?", Integer.class, combo)).isZero();
        send(get("/api/v1/concessions/" + combo), manager, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.components.length()").value(2));
    }

    @Test void rejectsInvalidCombos() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        int a = createItem(manager, "A " + key, 1000), inactive = createItem(manager, "B " + key, 1000), other = createItem(manager, "C " + key, 1000);
        int nested = createCombo(manager, "Nested " + key, comp(other, 1));
        send(patch("/api/v1/concessions/" + inactive + "/status"), manager, Map.of("active", false)).andExpect(status().isOk());
        String url = "/api/v1/concessions/combos";
        send(post(url), manager, comboBody("N1 " + key, 1000, comp(a, 1), comp(a, 2))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONCESSION_COMBO_INVALID"));
        send(post(url), manager, comboBody("N2 " + key, 1000, comp(999999, 1))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONCESSION_COMBO_INVALID"));
        send(post(url), manager, comboBody("N3 " + key, 1000, comp(nested, 1))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONCESSION_COMBO_INVALID"));
        send(post(url), manager, comboBody("N4 " + key, 1000, comp(inactive, 1))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONCESSION_COMBO_INVALID"));     // món đang ngừng bán
        send(post(url), manager, comboBody("N5 " + key, 1000)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        send(post(url), manager, comboBody("N6 " + key, 1000, comp(a, 0))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        send(post(url), manager, comboBody(" ", 1000, comp(a, 1))).andExpect(status().isBadRequest());
        send(post(url), manager, comboBody("N7 " + key, -1, comp(a, 1))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from concession_items where name like ?", Integer.class, "N_ " + key)).isZero();
    }

    @Test void updatesComboComponentsAsAWhole() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        int a = createItem(manager, "A " + key, 1000), b = createItem(manager, "B " + key, 1000), c = createItem(manager, "C " + key, 1000);
        int combo = createCombo(manager, "Combo " + key, comp(a, 1), comp(b, 1));
        send(patch("/api/v1/concessions/combos/" + combo), manager, comboBody("Combo mới " + key, 88000, comp(b, 3), comp(c, 1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Combo mới " + key)).andExpect(jsonPath("$.data.components.length()").value(2));
        assertThat(jdbc.queryForObject("select count(*) from combo_items where combo_id=? and component_item_id=?", Integer.class, combo, a)).isZero();
        assertThat(jdbc.queryForObject("select quantity from combo_items where combo_id=? and component_item_id=?", Integer.class, combo, b)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from combo_items where combo_id=? and component_item_id=?", Integer.class, combo, c)).isEqualTo(1);
        send(patch("/api/v1/concessions/combos/" + a), manager, comboBody("X " + key, 1, comp(b, 1))).andExpect(status().isNotFound());       // id là món lẻ
        send(patch("/api/v1/concessions/combos/" + combo), manager, comboBody(("A " + key).toUpperCase(), 1, comp(b, 1))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCESSION_NAME_EXISTS"));
        send(patch("/api/v1/concessions/combos/" + combo), manager, comboBody("Combo mới " + key, 1, comp(999999, 1))).andExpect(status().isBadRequest());
    }

    @Test void statusRulesProtectActiveCombos() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        int item = createItem(manager, "Món " + key, 1000), combo = createCombo(manager, "Combo " + key, comp(item, 1));
        send(patch("/api/v1/concessions/" + item + "/status"), manager, Map.of("active", false)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCESSION_ITEM_IN_ACTIVE_COMBO"));
        send(patch("/api/v1/concessions/" + combo + "/status"), manager, Map.of("active", false)).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));
        send(patch("/api/v1/concessions/" + item + "/status"), manager, Map.of("active", false)).andExpect(status().isOk());
        send(patch("/api/v1/concessions/" + combo + "/status"), manager, Map.of("active", true)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCESSION_COMBO_COMPONENT_INACTIVE"));
        send(patch("/api/v1/concessions/" + item + "/status"), manager, Map.of("active", true)).andExpect(status().isOk());
        send(patch("/api/v1/concessions/" + combo + "/status"), manager, Map.of("active", true)).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(true));
        send(patch("/api/v1/concessions/999999/status"), manager, Map.of("active", true)).andExpect(status().isNotFound());
        send(patch("/api/v1/concessions/" + item + "/status"), manager, Map.of()).andExpect(status().isBadRequest());
    }

    @Test void nonManagersOnlySeeActiveItemsAndListFiltersWork() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        int shown = createItem(manager, "Hiện " + key, 1000), hidden = createItem(manager, "Ẩn " + key, 1000), combo = createCombo(manager, "Combo " + key, comp(shown, 1));
        send(patch("/api/v1/concessions/" + hidden + "/status"), manager, Map.of("active", false)).andExpect(status().isOk());
        for (UserRole role : List.of(UserRole.CUSTOMER, UserRole.STAFF)) {
            String token = tokenFor(role);
            send(get("/api/v1/concessions").param("keyword", key), token, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2));
            send(get("/api/v1/concessions").param("keyword", key).param("active", "false"), token, null).andExpect(jsonPath("$.data.totalElements").value(2));
            send(get("/api/v1/concessions/" + hidden), token, null).andExpect(status().isNotFound());
            send(get("/api/v1/concessions/" + shown), token, null).andExpect(status().isOk());
        }
        send(get("/api/v1/concessions").param("keyword", key), manager, null).andExpect(jsonPath("$.data.totalElements").value(3));
        send(get("/api/v1/concessions").param("keyword", key).param("combo", "true"), manager, null).andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.items[0].id").value(combo));
        send(get("/api/v1/concessions").param("keyword", key).param("active", "false"), manager, null).andExpect(jsonPath("$.data.totalElements").value(1));
        send(get("/api/v1/concessions").param("keyword", key).param("size", "2").param("page", "1"), manager, null).andExpect(jsonPath("$.data.items.length()").value(1)).andExpect(jsonPath("$.data.totalPages").value(2));
        send(get("/api/v1/concessions").param("keyword", "%" + key), manager, null).andExpect(jsonPath("$.data.totalElements").value(0));
        send(get("/api/v1/concessions").param("active", "maybe"), manager, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        send(get("/api/v1/concessions").param("size", "101"), manager, null).andExpect(status().isBadRequest());
        send(get("/api/v1/concessions").param("page", "-1"), manager, null).andExpect(status().isBadRequest());
    }

    @Test void databaseEnforcesPriceAndComboRules() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        int a = createItem(manager, "A " + key, 1000), combo = createCombo(manager, "Combo " + key, comp(a, 1));
        assertThatThrownBy(() -> jdbc.update("insert into concession_items(name,price,is_combo) values(?,-1,false)", "Neg " + key)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into concession_items(name,price,is_combo) values(?,1,false)", "A " + key)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into combo_items(combo_id,component_item_id,quantity) values(?,?,1)", combo, combo)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update combo_items set quantity=0 where combo_id=?", combo)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from concession_items where item_id=?", a)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void onlyManagerCanWriteTheCatalog() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        int item = createItem(manager, "Món " + key, 1000);
        send(get("/api/v1/concessions"), null, null).andExpect(status().isUnauthorized());
        send(post("/api/v1/concessions"), null, Map.of("name", "X", "price", 1)).andExpect(status().isUnauthorized());
        for (UserRole role : List.of(UserRole.STAFF, UserRole.CUSTOMER)) {
            String token = tokenFor(role);
            send(post("/api/v1/concessions"), token, Map.of("name", "Denied " + unique(), "price", 1)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            send(patch("/api/v1/concessions/" + item), token, Map.of("name", "Denied", "price", 1)).andExpect(status().isForbidden());
            send(patch("/api/v1/concessions/" + item + "/status"), token, Map.of("active", false)).andExpect(status().isForbidden());
            send(post("/api/v1/concessions/combos"), token, comboBody("Denied " + unique(), 1, comp(item, 1))).andExpect(status().isForbidden());
            send(patch("/api/v1/concessions/combos/" + item), token, comboBody("Denied", 1, comp(item, 1))).andExpect(status().isForbidden());
        }
        send(get("/api/v1/concessions/" + item), manager, null).andExpect(jsonPath("$.data.active").value(true));
    }
}
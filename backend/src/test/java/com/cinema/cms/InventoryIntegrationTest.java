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

@SpringBootTest(properties = {"app.jwt.secret=" + InventoryIntegrationTest.SECRET, "app.jwt.ttl-seconds=1800"})
@AutoConfigureMockMvc
class InventoryIntegrationTest {
    static final String SECRET = "inventory-test-only-secret-at-least-32-bytes--";
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

    @Test void restockRecordsBeforeAfterManagerNoteAndTimestamp() throws Exception {
        User manager = newUser(UserRole.MANAGER); String token = jwt.issue(manager);
        int item = createItem(token, "Bắp " + unique(), 55000);
        send(post("/api/v1/inventory/" + item + "/restocks"), token, Map.of("quantity", 20, "note", "  Lô A  ")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("RESTOCK")).andExpect(jsonPath("$.data.quantity").value(20))
                .andExpect(jsonPath("$.data.beforeQuantity").value(0)).andExpect(jsonPath("$.data.afterQuantity").value(20))
                .andExpect(jsonPath("$.data.managerId").value(manager.getId())).andExpect(jsonPath("$.data.managerName").value(manager.getFullName()))
                .andExpect(jsonPath("$.data.note").value("Lô A")).andExpect(jsonPath("$.data.createdAt").isNotEmpty());
        send(post("/api/v1/inventory/" + item + "/restocks"), token, Map.of("quantity", 50)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.beforeQuantity").value(20)).andExpect(jsonPath("$.data.afterQuantity").value(70)).andExpect(jsonPath("$.data.note").isEmpty());
        assertThat(stockOf(item)).isEqualTo(70);
        assertThat(movementCount(item)).isEqualTo(2);
        Map<String, Object> row = jdbc.queryForMap("select movement_type, quantity, before_quantity, after_quantity, manager_id, note from inventory_transactions where item_id=? order by movement_id limit 1", item);
        assertThat(row.get("movement_type")).isEqualTo("RESTOCK");
        assertThat(((Number) row.get("manager_id")).longValue()).isEqualTo(manager.getId());
        assertThat(row.get("note")).isEqualTo("Lô A");
    }

    @Test void rejectsInvalidQuantitiesWithoutChangingStock() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int item = createItem(manager, "Nước " + unique(), 30000);
        String url = "/api/v1/inventory/" + item + "/restocks";
        send(post(url), manager, Map.of("quantity", 0)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STOCK_QUANTITY"));
        send(post(url), manager, Map.of("quantity", -5)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STOCK_QUANTITY"));
        for (Object body : List.<Object>of(Map.of("quantity", 1.5), Map.of("quantity", 5.0), Map.of("quantity", "abc"), Map.of("note", "thiếu số lượng"),
                Map.of("quantity", 1000000000), Map.of("quantity", 5, "note", "x".repeat(501)), Map.of("quantity", 5, "managerId", 1), Map.of())) {
            send(post(url), manager, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        assertThat(stockOf(item)).isZero();
        assertThat(movementCount(item)).isZero();
        jdbc.update("update concession_inventory set stock_quantity=2147483000 where item_id=?", item);
        send(post(url), manager, Map.of("quantity", 1000)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STOCK_QUANTITY"));
        assertThat(stockOf(item)).isEqualTo(2147483000);
        assertThat(movementCount(item)).isZero();
    }

    @Test void restockOfUnknownItemsAndCombosIsNotFound() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        int item = createItem(manager, "Món " + key, 1000), combo = createCombo(manager, "Combo " + key, comp(item, 1));
        send(post("/api/v1/inventory/999999/restocks"), manager, Map.of("quantity", 5)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONCESSION_ITEM_NOT_FOUND"));
        send(post("/api/v1/inventory/" + combo + "/restocks"), manager, Map.of("quantity", 5)).andExpect(status().isNotFound());
        send(get("/api/v1/inventory/999999/history"), manager, null).andExpect(status().isNotFound());
    }

    @Test void concurrentRestocksNeverLoseAnUpdate() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int item = createItem(manager, "Race " + unique(), 1000);
        String url = "/api/v1/inventory/" + item + "/restocks";
        for (int round = 0; round < 5; round++) {
            List<MvcResult> results = concurrently(() -> send(post(url), manager, Map.of("quantity", 10)).andReturn(), () -> send(post(url), manager, Map.of("quantity", 15)).andReturn());
            assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
        }
        assertThat(stockOf(item)).isEqualTo(125);
        List<Map<String, Object>> rows = jdbc.queryForList("select quantity, before_quantity, after_quantity from inventory_transactions where item_id=? order by movement_id", item);
        assertThat(rows).hasSize(10);
        int expectedBefore = 0;
        for (Map<String, Object> row : rows) {       
            assertThat(((Number) row.get("before_quantity")).intValue()).isEqualTo(expectedBefore);
            expectedBefore = ((Number) row.get("after_quantity")).intValue();
        }
        assertThat(expectedBefore).isEqualTo(125);
    }

    @Test void inventoryListFiltersByStatusAndKeyword() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        int out = createItem(manager, key + "-A", 1000), low = createItem(manager, key + "-B", 1000), edge = createItem(manager, key + "-C", 1000), ok = createItem(manager, key + "-D", 1000);
        jdbc.update("update concession_inventory set stock_quantity=5 where item_id=?", low);
        jdbc.update("update concession_inventory set stock_quantity=10 where item_id=?", edge);
        jdbc.update("update concession_inventory set stock_quantity=11 where item_id=?", ok);
        send(get("/api/v1/inventory").param("keyword", key), manager, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.items[0].name").value(key + "-A")).andExpect(jsonPath("$.data.items[0].status").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.data.items[3].status").value("IN_STOCK")).andExpect(jsonPath("$.data.items[1].lowStockThreshold").value(10));
        send(get("/api/v1/inventory").param("keyword", key).param("status", "OUT_OF_STOCK"), manager, null).andExpect(jsonPath("$.data.totalElements").value(1));
        send(get("/api/v1/inventory").param("keyword", key).param("status", "low_stock"), manager, null).andExpect(jsonPath("$.data.totalElements").value(2));
        send(get("/api/v1/inventory").param("keyword", key).param("status", "IN_STOCK"), manager, null).andExpect(jsonPath("$.data.totalElements").value(1));
        send(get("/api/v1/inventory").param("keyword", key).param("size", "2").param("page", "1"), manager, null).andExpect(jsonPath("$.data.items.length()").value(2)).andExpect(jsonPath("$.data.totalPages").value(2));
        send(get("/api/v1/inventory").param("status", "BANANA"), manager, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        send(get("/api/v1/inventory").param("size", "101"), manager, null).andExpect(status().isBadRequest());
        assertThat(out).isNotEqualTo(low);
    }

    @Test void alertsListLowActiveItemsForManagerAndStaffOnly() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), staff = tokenFor(UserRole.STAFF), customer = tokenFor(UserRole.CUSTOMER), key = unique();
        int low = createItem(manager, "Low " + key, 1000), hidden = createItem(manager, "Hidden " + key, 1000);
        send(patch("/api/v1/concessions/" + hidden + "/status"), manager, Map.of("active", false)).andExpect(status().isOk());
        for (String token : List.of(manager, staff)) {
            send(get("/api/v1/inventory/alerts"), token, null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.name=='Low " + key + "')]").isNotEmpty()).andExpect(jsonPath("$.data[?(@.name=='Hidden " + key + "')]").isEmpty());
        }
        send(post("/api/v1/inventory/" + low + "/restocks"), manager, Map.of("quantity", 50)).andExpect(status().isCreated());
        send(get("/api/v1/inventory/alerts"), staff, null).andExpect(jsonPath("$.data[?(@.name=='Low " + key + "')]").isEmpty());
        send(get("/api/v1/inventory/alerts"), customer, null).andExpect(status().isForbidden());
        send(get("/api/v1/inventory/alerts"), null, null).andExpect(status().isUnauthorized());
    }

    @Test void restockHistoryFiltersAndOrdersNewestFirst() throws Exception {
        User m1 = newUser(UserRole.MANAGER), m2 = newUser(UserRole.MANAGER); String t1 = jwt.issue(m1), t2 = jwt.issue(m2), key = unique();
        int a = createItem(t1, "A " + key, 1000), b = createItem(t1, "B " + key, 1000);
        send(post("/api/v1/inventory/" + a + "/restocks"), t1, Map.of("quantity", 5)).andExpect(status().isCreated());
        send(post("/api/v1/inventory/" + a + "/restocks"), t2, Map.of("quantity", 7)).andExpect(status().isCreated());
        send(post("/api/v1/inventory/" + b + "/restocks"), t1, Map.of("quantity", 9)).andExpect(status().isCreated());
        send(get("/api/v1/inventory/restocks").param("itemId", String.valueOf(a)), t1, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[0].quantity").value(7)).andExpect(jsonPath("$.data.items[0].managerName").value(m2.getFullName())).andExpect(jsonPath("$.data.items[0].itemName").value("A " + key));
        send(get("/api/v1/inventory/restocks").param("itemId", String.valueOf(a)).param("managerId", String.valueOf(m1.getId())), t1, null).andExpect(jsonPath("$.data.totalElements").value(1));
        send(get("/api/v1/inventory/restocks").param("itemId", String.valueOf(a)).param("from", "2999-01-01T00:00:00Z"), t1, null).andExpect(jsonPath("$.data.totalElements").value(0));
        send(get("/api/v1/inventory/restocks").param("itemId", String.valueOf(a)).param("to", "2000-01-01T00:00:00Z"), t1, null).andExpect(jsonPath("$.data.totalElements").value(0));
        send(get("/api/v1/inventory/restocks").param("itemId", String.valueOf(a)).param("from", "2000-01-01T00:00:00Z").param("to", "2999-01-01T00:00:00Z"), t1, null).andExpect(jsonPath("$.data.totalElements").value(2));
        send(get("/api/v1/inventory/restocks").param("from", "2999-01-01T00:00:00Z").param("to", "2000-01-01T00:00:00Z"), t1, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        send(get("/api/v1/inventory/restocks").param("from", "01/01/2026"), t1, null).andExpect(status().isBadRequest());
        send(get("/api/v1/inventory/restocks").param("itemId", "abc"), t1, null).andExpect(status().isBadRequest());
        send(get("/api/v1/inventory/restocks").param("size", "101"), t1, null).andExpect(status().isBadRequest());
        send(get("/api/v1/inventory/" + a + "/history"), t1, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2)).andExpect(jsonPath("$.data.items[0].afterQuantity").value(12));
    }

    @Test void databaseKeepsStockAndHistoryConsistent() throws Exception {
        User manager = newUser(UserRole.MANAGER);
        int item = createItem(jwt.issue(manager), "DB " + unique(), 1000);
        String insert = "insert into inventory_transactions(item_id,movement_type,quantity,before_quantity,after_quantity,manager_id) values(?,?,?,?,?,?)";
        assertThatThrownBy(() -> jdbc.update("update concession_inventory set stock_quantity=-1 where item_id=?", item)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, item, "RESTOCK", 10, 5, 100, manager.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, item, "RESTOCK", 0, 5, 5, manager.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into inventory_transactions(item_id,movement_type,quantity,before_quantity,after_quantity,manager_id) values(?,'RESTOCK',10,5,15,null)", item)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, item, "BOGUS", 10, 5, 15, manager.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(movementCount(item)).isZero();
    }

    @Test void onlyManagerCanRestockAndReadInventory() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int item = createItem(manager, "Auth " + unique(), 1000);
        send(get("/api/v1/inventory"), null, null).andExpect(status().isUnauthorized());
        send(post("/api/v1/inventory/" + item + "/restocks"), null, Map.of("quantity", 1)).andExpect(status().isUnauthorized());
        for (UserRole role : List.of(UserRole.STAFF, UserRole.CUSTOMER)) {
            String token = tokenFor(role);
            send(post("/api/v1/inventory/" + item + "/restocks"), token, Map.of("quantity", 5)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            send(get("/api/v1/inventory"), token, null).andExpect(status().isForbidden());
            send(get("/api/v1/inventory/restocks"), token, null).andExpect(status().isForbidden());
            send(get("/api/v1/inventory/" + item + "/history"), token, null).andExpect(status().isForbidden());
        }
        assertThat(stockOf(item)).isZero();
        assertThat(movementCount(item)).isZero();
    }
}
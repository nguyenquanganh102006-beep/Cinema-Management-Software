package com.cinema.cms;

import com.cinema.cms.entity.*;
import com.cinema.cms.repository.UserRepository;
import com.cinema.cms.service.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.jwt.secret=" + StaffIntegrationTest.SECRET, "app.jwt.ttl-seconds=1800"})
@AutoConfigureMockMvc
class StaffIntegrationTest {
    static final String SECRET = "staff-test-only-secret-at-least-32-bytes-long";
    static final String PASSWORD = "TestPass123!";
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
    private String phone() { return "09" + String.format("%010d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 10000000000L)); }
    private User newUser(UserRole role) {
        String s = unique();
        return users.saveAndFlush(new User("t_" + s, "Staff Tester " + s, s + "@cinema.test", phone(), passwords.encode(PASSWORD), role));
    }
    private String tokenFor(UserRole role) { return jwt.issue(newUser(role)); }
    private Map<String, Object> staffBody(String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", "stf." + key); m.put("fullName", "Nhân viên " + key); m.put("email", key + "@cinema.test");
        m.put("phone", phone()); m.put("password", PASSWORD);
        return m;
    }
    private ResultActions send(MockHttpServletRequestBuilder request, String token, Object content) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (content != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(content));
        return mvc.perform(request);
    }
    private long createStaff(String manager, Map<String, Object> body) throws Exception {
        String json = send(post("/api/v1/staff"), manager, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data").path("id").asLong();
    }
    private ResultActions login(String username, String password) throws Exception {
        return send(post("/api/v1/auth/login"), null, Map.of("username", username, "password", password));
    }

    @Test void createsStaffWithHashedPasswordFixedRoleAndNoSecretsInResponse() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        Map<String, Object> body = staffBody(key);
        body.put("username", "  STF." + key.toUpperCase() + " ");       // được chuẩn hóa về chữ thường
        String raw = send(post("/api/v1/staff"), manager, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("stf." + key))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.managerPinHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain(PASSWORD).doesNotContain("$2");
        long id = mapper.readTree(raw).path("data").path("id").asLong();
        assertThat(jdbc.queryForObject("select role::text from users where user_id=?", String.class, id)).isEqualTo("STAFF");
        String hash = jdbc.queryForObject("select password_hash from users where user_id=?", String.class, id);
        assertThat(hash).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        login("stf." + key, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.data.user.role").value("STAFF"));
    }

    @Test void requestCannotAssignManagerRoleOrOtherInternalFields() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        for (Map.Entry<String, Object> extra : Map.<String, Object>of("role", "MANAGER", "status", "LOCKED", "managerPinHash", "x", "securityVersion", 9).entrySet()) {
            Map<String, Object> body = staffBody(key); body.put(extra.getKey(), extra.getValue());
            send(post("/api/v1/staff"), manager, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        assertThat(jdbc.queryForObject("select count(*) from users where username=?", Integer.class, "stf." + key)).isZero();
    }

    @Test void rejectsInvalidInput() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        List<Map<String, Object>> invalid = new ArrayList<>();
        Map<String, Object> m;
        m = staffBody(key); m.put("username", "ab"); invalid.add(m);                  // quá ngắn
        m = staffBody(key); m.put("username", "has space"); invalid.add(m);           // ký tự không hợp lệ
        m = staffBody(key); m.put("fullName", "  "); invalid.add(m);
        m = staffBody(key); m.put("email", "not-an-email"); invalid.add(m);
        m = staffBody(key); m.put("phone", "09abc"); invalid.add(m);
        m = staffBody(key); m.put("password", "short1!"); invalid.add(m); 
        m = staffBody(key); m.put("password", "ấ".repeat(30)); invalid.add(m); 
        m = staffBody(key); m.remove("email"); invalid.add(m);
        for (Map<String, Object> body : invalid) {
            send(post("/api/v1/staff"), manager, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test void rejectsDuplicateUsernameEmailAndPhone() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        Map<String, Object> first = staffBody(key);
        createStaff(manager, first);
        Map<String, Object> sameUsername = staffBody(unique()); sameUsername.put("username", ((String) first.get("username")).toUpperCase());
        send(post("/api/v1/staff"), manager, sameUsername).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"));
        Map<String, Object> sameEmail = staffBody(unique()); sameEmail.put("email", first.get("email"));
        send(post("/api/v1/staff"), manager, sameEmail).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
        Map<String, Object> samePhone = staffBody(unique()); samePhone.put("phone", first.get("phone"));
        send(post("/api/v1/staff"), manager, samePhone).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHONE_ALREADY_EXISTS"));
    }

    @Test void updatesProfileButNeverUsernameOrRole() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        long id = createStaff(manager, staffBody(key));
        String newKey = unique(), newPhone = phone();
        send(patch("/api/v1/staff/" + id), manager, Map.of("fullName", " Tên Mới ", "email", newKey.toUpperCase() + "@cinema.test", "phone", newPhone))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fullName").value("Tên Mới"))
                .andExpect(jsonPath("$.data.email").value(newKey + "@cinema.test")).andExpect(jsonPath("$.data.username").value("stf." + key));
        send(patch("/api/v1/staff/" + id), manager, Map.of("fullName", "X", "email", "a@cinema.test", "phone", newPhone, "username", "hacked"))
                .andExpect(status().isBadRequest());
        Map<String, Object> other = staffBody(unique()); createStaff(manager, other);
        send(patch("/api/v1/staff/" + id), manager, Map.of("fullName", "X", "email", other.get("email"), "phone", newPhone))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
        send(patch("/api/v1/staff/" + id), manager, Map.of("fullName", "X", "email", "ok" + newKey + "@cinema.test", "phone", other.get("phone")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHONE_ALREADY_EXISTS"));
        send(patch("/api/v1/staff/" + id), manager, Map.of("fullName", "Vẫn ổn", "email", newKey + "@cinema.test", "phone", newPhone)).andExpect(status().isOk());
    }

    @Test void managersAndCustomersAreNotVisibleThroughStaffApi() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        long managerId = newUser(UserRole.MANAGER).getId(), customerId = newUser(UserRole.CUSTOMER).getId();
        for (long id : new long[]{managerId, customerId, 999999999L}) {
            send(get("/api/v1/staff/" + id), manager, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STAFF_NOT_FOUND"));
            send(patch("/api/v1/staff/" + id), manager, Map.of("fullName", "X", "email", "x@cinema.test", "phone", "0900000001")).andExpect(status().isNotFound());
            send(patch("/api/v1/staff/" + id + "/status"), manager, Map.of("active", false)).andExpect(status().isNotFound());
        }
        assertThat(jdbc.queryForObject("select status::text from users where user_id=?", String.class, managerId)).isEqualTo("ACTIVE");
    }

    @Test void deactivationBlocksLoginAndOldTokensEvenAfterReactivation() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        long id = createStaff(manager, staffBody(key));
        String staffToken = login("stf." + key, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String oldToken = mapper.readTree(staffToken).path("data").path("accessToken").asText();
        send(get("/api/v1/auth/me"), oldToken, null).andExpect(status().isOk());

        send(patch("/api/v1/staff/" + id + "/status"), manager, Map.of("active", false)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INACTIVE"));
        login("stf." + key, PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
        send(get("/api/v1/auth/me"), oldToken, null).andExpect(status().isUnauthorized()); 

        send(patch("/api/v1/staff/" + id + "/status"), manager, Map.of("active", true)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));
        send(get("/api/v1/auth/me"), oldToken, null).andExpect(status().isUnauthorized()); 
        login("stf." + key, PASSWORD).andExpect(status().isOk());
        send(patch("/api/v1/staff/" + id + "/status"), manager, Map.of("active", true)).andExpect(status().isOk());
        send(patch("/api/v1/staff/" + id + "/status"), manager, Map.of()).andExpect(status().isBadRequest());
    }

    @Test void listShowsOnlyStaffFiltersAndPaginates() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), key = unique();
        for (int i = 1; i <= 3; i++) createStaff(manager, staffBody(key + i));
        long inactive = createStaff(manager, staffBody(key + "z"));
        send(patch("/api/v1/staff/" + inactive + "/status"), manager, Map.of("active", false)).andExpect(status().isOk());
        User sameNameManager = users.saveAndFlush(new User("mgr." + key, "Nhân viên " + key + "m", key + "m@cinema.test", phone(), passwords.encode(PASSWORD), UserRole.MANAGER));
        users.saveAndFlush(new User("cus." + key, "Nhân viên " + key + "c", key + "c@cinema.test", phone(), passwords.encode(PASSWORD), UserRole.CUSTOMER));

        String all = send(get("/api/v1/staff").param("keyword", key), manager, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4)).andReturn().getResponse().getContentAsString();
        for (JsonNode item : mapper.readTree(all).path("data").path("items")) assertThat(item.path("username").asText()).isNotEqualTo(sameNameManager.getUsername());
        send(get("/api/v1/staff").param("keyword", "STF." + key.toUpperCase() + "1"), manager, null).andExpect(jsonPath("$.data.totalElements").value(1));
        send(get("/api/v1/staff").param("keyword", key).param("status", "inactive"), manager, null).andExpect(jsonPath("$.data.totalElements").value(1));
        send(get("/api/v1/staff").param("keyword", key).param("size", "2").param("page", "1"), manager, null)
                .andExpect(jsonPath("$.data.items.length()").value(2)).andExpect(jsonPath("$.data.totalPages").value(2));
        send(get("/api/v1/staff").param("keyword", "%" + key), manager, null).andExpect(jsonPath("$.data.totalElements").value(0));
        send(get("/api/v1/staff").param("status", "BANANA"), manager, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        send(get("/api/v1/staff").param("size", "101"), manager, null).andExpect(status().isBadRequest());
        send(get("/api/v1/staff").param("page", "-1"), manager, null).andExpect(status().isBadRequest());
    }

    @Test void onlyManagerCanUseStaffApi() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        long id = createStaff(manager, staffBody(unique()));
        send(get("/api/v1/staff"), null, null).andExpect(status().isUnauthorized());
        send(post("/api/v1/staff"), null, staffBody(unique())).andExpect(status().isUnauthorized());
        for (UserRole role : List.of(UserRole.STAFF, UserRole.CUSTOMER)) {
            String token = tokenFor(role);
            send(get("/api/v1/staff"), token, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            send(get("/api/v1/staff/" + id), token, null).andExpect(status().isForbidden());
            send(post("/api/v1/staff"), token, staffBody(unique())).andExpect(status().isForbidden());
            send(patch("/api/v1/staff/" + id), token, Map.of("fullName", "X", "email", "x@cinema.test", "phone", "0900000002")).andExpect(status().isForbidden());
            send(patch("/api/v1/staff/" + id + "/status"), token, Map.of("active", false)).andExpect(status().isForbidden());
        }
    }
}
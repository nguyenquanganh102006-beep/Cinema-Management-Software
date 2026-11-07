package com.cinema.cms;

import com.cinema.cms.entity.*;
import com.cinema.cms.repository.UserRepository;
import com.cinema.cms.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.jwt.secret=" + MovieIntegrationTest.SECRET, "app.jwt.ttl-seconds=1800",
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1"})
@AutoConfigureMockMvc
class MovieIntegrationTest {
    static final String SECRET = "movie-test-only-secret-at-least-32-bytes-long";
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

    private String tokenFor(UserRole role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = users.saveAndFlush(new User("m_" + suffix, "Movie Tester", suffix + "@cinema.test",
                "09" + String.format("%010d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 10000000000L)),
                passwords.encode("TestPass123!"), role));
        return jwt.issue(user);
    }
    private Map<String, Object> body(String title) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title); m.put("durationMinutes", 120); m.put("ageRating", "T13"); m.put("releaseDate", "2026-11-14");
        return m;
    }
    private ResultActions send(MockHttpServletRequestBuilder request, String token, Object content) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (content != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(content));
        return mvc.perform(request);
    }
    private long createMovie(String managerToken, Map<String, Object> content) throws Exception {
        String json = send(post("/api/v1/movies"), managerToken, content).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data").path("id").asLong();
    }
    private String unique() { return UUID.randomUUID().toString().substring(0, 8); }

    @Test void managerCreatesMovieTrimsInputAndStoresIt() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        Map<String, Object> content = body("  Phim " + unique() + "  ");
        content.put("description", "   "); content.put("genre", "  Hành động ");
        long id = 0;
        String json = send(post("/api/v1/movies"), manager, content).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.ageRating").value("T13"))
                .andExpect(jsonPath("$.data.genre").value("Hành động"))
                .andExpect(jsonPath("$.data.description").isEmpty())
                .andReturn().getResponse().getContentAsString();
        id = mapper.readTree(json).path("data").path("id").asLong();
        assertThat(jdbc.queryForObject("select title from movies where movie_id=?", String.class, id)).doesNotStartWith(" ").doesNotEndWith(" ");
        assertThat(jdbc.queryForObject("select age_rating::text from movies where movie_id=?", String.class, id)).isEqualTo("T13");
    }

    @Test void rejectsInvalidInput() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        List<Map<String, Object>> invalid = new ArrayList<>();
        Map<String, Object> m;
        m = body(" "); invalid.add(m); 
        m = body("A"); m.put("title", "x".repeat(201)); invalid.add(m); 
        m = body("A"); m.put("durationMinutes", 0); invalid.add(m); 
        m = body("A"); m.put("durationMinutes", -5); invalid.add(m);
        m = body("A"); m.remove("ageRating"); invalid.add(m); 
        m = body("A"); m.put("ageRating", "T99"); invalid.add(m);
        m = body("A"); m.put("releaseDate", "14/11/2026"); invalid.add(m);
        m = body("A"); m.remove("releaseDate"); invalid.add(m); 
        m = body("A"); m.put("poster", "javascript:alert(1)"); invalid.add(m); 
        m = body("A"); m.put("trailer", "ftp://example.test/t.mp4"); invalid.add(m);
        m = body("A"); m.put("role", "MANAGER"); invalid.add(m); 
        for (Map<String, Object> content : invalid) {
            send(post("/api/v1/movies"), manager, content).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        send(patch("/api/v1/movies/1/status"), manager, Map.of()).andExpect(status().isBadRequest());
    }

    @Test void updateReplacesFieldsAndClearsOptionalOnes() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        Map<String, Object> content = body("Update " + unique()); content.put("description", "Mô tả"); content.put("poster", "https://cdn.example.test/p.jpg");
        long id = createMovie(manager, content);
        Map<String, Object> change = body("Updated " + unique()); change.put("durationMinutes", 95); change.put("ageRating", "P");
        send(patch("/api/v1/movies/" + id), manager, change).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.durationMinutes").value(95)).andExpect(jsonPath("$.data.ageRating").value("P"))
                .andExpect(jsonPath("$.data.description").isEmpty()).andExpect(jsonPath("$.data.poster").isEmpty())
                .andExpect(jsonPath("$.data.active").value(true));
        send(patch("/api/v1/movies/999999999"), manager, body("Ghost")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"));
    }

    @Test void deactivatedMovieIsHiddenFromNonManagers() throws Exception {
        String manager = tokenFor(UserRole.MANAGER), customer = tokenFor(UserRole.CUSTOMER);
        String key = unique();
        long id = createMovie(manager, body("Hidden " + key));
        send(patch("/api/v1/movies/" + id + "/status"), manager, Map.of("active", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
        send(get("/api/v1/movies/" + id), customer, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"));
        send(get("/api/v1/movies").param("keyword", key), customer, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        send(get("/api/v1/movies").param("keyword", key).param("active", "false"), customer, null).andExpect(jsonPath("$.data.totalElements").value(0)); // khách không tự bỏ lọc được
        send(get("/api/v1/movies/" + id), manager, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));
        send(get("/api/v1/movies").param("keyword", key).param("active", "false"), manager, null).andExpect(jsonPath("$.data.totalElements").value(1));
        send(patch("/api/v1/movies/" + id + "/status"), manager, Map.of("active", true)).andExpect(status().isOk());
        send(get("/api/v1/movies/" + id), customer, null).andExpect(status().isOk());
    }

    @Test void listFiltersByKeywordPaginatesAndEscapesWildcards() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        String key = unique();
        for (int i = 1; i <= 3; i++) createMovie(manager, body("Zeta " + key + " " + i));
        createMovie(manager, body("100%" + key));
        send(get("/api/v1/movies").param("keyword", key.toUpperCase()), manager, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4));
        send(get("/api/v1/movies").param("keyword", key).param("size", "2").param("page", "1"), manager, null)
                .andExpect(jsonPath("$.data.items.length()").value(2)).andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.page").value(1));
        send(get("/api/v1/movies").param("keyword", "%" + key), manager, null).andExpect(jsonPath("$.data.totalElements").value(1));
        send(get("/api/v1/movies").param("size", "101"), manager, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        send(get("/api/v1/movies").param("page", "-1"), manager, null).andExpect(status().isBadRequest());
    }

    @Test void onlyManagerCanWrite() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        long id = createMovie(manager, body("Auth " + unique()));
        send(get("/api/v1/movies"), null, null).andExpect(status().isUnauthorized());
        send(post("/api/v1/movies"), null, body("No token")).andExpect(status().isUnauthorized());
        for (UserRole role : List.of(UserRole.STAFF, UserRole.CUSTOMER)) {
            String token = tokenFor(role);
            send(post("/api/v1/movies"), token, body("Denied")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            send(patch("/api/v1/movies/" + id), token, body("Denied")).andExpect(status().isForbidden());
            send(patch("/api/v1/movies/" + id + "/status"), token, Map.of("active", false)).andExpect(status().isForbidden());
            send(get("/api/v1/movies"), token, null).andExpect(status().isOk());
            send(get("/api/v1/movies/" + id), token, null).andExpect(status().isOk());
        }
    }

    @Test void databaseRejectsNonPositiveDuration() {
        assertThatThrownBy(() -> jdbc.update("insert into movies(title,duration_minutes,age_rating,release_date) values('Bad',0,'P',current_date)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
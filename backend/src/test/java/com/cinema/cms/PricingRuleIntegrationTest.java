package com.cinema.cms;

import com.cinema.cms.entity.*;
import com.cinema.cms.repository.UserRepository;
import com.cinema.cms.service.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.jwt.secret=" + PricingRuleIntegrationTest.SECRET, "app.jwt.ttl-seconds=1800"})
@AutoConfigureMockMvc
@Transactional
class PricingRuleIntegrationTest {
    static final String SECRET = "pricing-test-only-secret-at-least-32-bytes-";
    static final String URL = "/api/v1/pricing-rules";
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
        String s = UUID.randomUUID().toString().substring(0, 8);
        return jwt.issue(users.saveAndFlush(new User("p_" + s, "Pricing Tester", s + "@cinema.test",
                "09" + String.format("%010d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 10000000000L)), passwords.encode("TestPass123!"), role)));
    }
    private ResultActions send(MockHttpServletRequestBuilder request, String token, Object content) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (content != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(content));
        return mvc.perform(request);
    }
    private int idOf(String code) { return jdbc.queryForObject("select rule_id from pricing_rules where rule_code=?", Integer.class, code); }
    private BigDecimal amountOf(String code) { return jdbc.queryForObject("select amount from pricing_rules where rule_code=?", BigDecimal.class, code); }
    private Map<String, Object> newRule(String code, String type, String target) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleCode", code); m.put("type", type); m.put("targetRef", target); m.put("amount", 12000);
        return m;
    }

    @Test void listsTheSeededRulesWithApprovedValuesInBusinessOrder() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        String json = send(get(URL), manager, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode items = mapper.readTree(json).path("data");
        assertThat(items).hasSize(8);
        List<String> codes = new ArrayList<>();
        Map<String, BigDecimal> amounts = new HashMap<>();
        for (JsonNode n : items) { codes.add(n.path("ruleCode").asText()); amounts.put(n.path("ruleCode").asText(), new BigDecimal(n.path("amount").asText())); }
        assertThat(codes).containsExactly("BASE_STANDARD", "SUR_3D", "SUR_IMAX", "SUR_2D", "SUR_STANDARD", "SUR_SWEETBOX", "SUR_VIP", "SUR_TIME");
        Map<String, Integer> approved = Map.of("BASE_STANDARD", 70000, "SUR_2D", 0, "SUR_3D", 20000, "SUR_IMAX", 40000,
                "SUR_STANDARD", 0, "SUR_VIP", 15000, "SUR_SWEETBOX", 15000, "SUR_TIME", 10000);
        approved.forEach((code, value) -> assertThat(amounts.get(code)).isEqualByComparingTo(BigDecimal.valueOf(value)));
        send(get(URL).param("type", "seat_surcharge"), manager, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3));
        send(get(URL).param("type", "FOO"), manager, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test void getsOneRuleOrReportsNotFound() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        send(get(URL + "/" + idOf("SUR_VIP")), manager, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.ruleCode").value("SUR_VIP"))
                .andExpect(jsonPath("$.data.type").value("SEAT_SURCHARGE")).andExpect(jsonPath("$.data.targetRef").value("VIP"));
        send(get(URL + "/999999"), manager, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRICING_RULE_NOT_FOUND"));
    }

    @Test void updatesOnlyAmountAndDescription() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int id = idOf("SUR_VIP");
        send(patch(URL + "/" + id), manager, Map.of("amount", 20000.5, "description", "  Ghế VIP mới  ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").value("Ghế VIP mới")).andExpect(jsonPath("$.data.ruleCode").value("SUR_VIP"))
                .andExpect(jsonPath("$.data.type").value("SEAT_SURCHARGE")).andExpect(jsonPath("$.data.targetRef").value("VIP"));
        assertThat(amountOf("SUR_VIP")).isEqualByComparingTo("20000.50");
        Map<String, Object> blank = new LinkedHashMap<>(); blank.put("amount", 15000); blank.put("description", "   ");
        send(patch(URL + "/" + id), manager, blank).andExpect(status().isOk()).andExpect(jsonPath("$.data.description").isEmpty());
        send(patch(URL + "/999999"), manager, Map.of("amount", 1)).andExpect(status().isNotFound());
    }

    @Test void rejectsInvalidUpdatesAndLeavesTheRuleUntouched() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        int id = idOf("SUR_VIP");
        List<Object> invalid = new ArrayList<>();
        invalid.add(Map.of("amount", -1));
        invalid.add(Map.of("amount", 100000000));
        invalid.add(Map.of("amount", 1.234));
        invalid.add(Map.of("description", "x"));
        invalid.add(Map.of("amount", 10, "description", "x".repeat(256)));
        invalid.add(Map.of("amount", 10, "ruleCode", "HACKED"));
        invalid.add(Map.of("amount", 10, "type", "BASE_PRICE"));
        invalid.add(Map.of("amount", 10, "targetRef", "IMAX"));
        Map<String, Object> nullAmount = new HashMap<>(); nullAmount.put("amount", null); invalid.add(nullAmount);
        for (Object body : invalid) send(patch(URL + "/" + id), manager, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(amountOf("SUR_VIP")).isEqualByComparingTo("15000");
    }

    @Test void createsARuleOnlyWhenTheCatalogCombinationHasNone() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        jdbc.update("delete from pricing_rules where rule_code='SUR_TIME'");
        Map<String, Object> body = newRule(" sur_time ", "TIME_SURCHARGE", "weekend_or_after_18h"); body.put("description", "  Phụ thu giờ  ");
        send(post(URL), manager, body).andExpect(status().isCreated()).andExpect(jsonPath("$.data.ruleCode").value("SUR_TIME"))
                .andExpect(jsonPath("$.data.targetRef").value("WEEKEND_OR_AFTER_18H")).andExpect(jsonPath("$.data.description").value("Phụ thu giờ"));
        assertThat(amountOf("SUR_TIME")).isEqualByComparingTo("12000");
        send(post(URL), manager, newRule("OTHER_TIME", "TIME_SURCHARGE", "WEEKEND_OR_AFTER_18H")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRICING_RULE_EXISTS"));
        send(post(URL), manager, newRule("SUR_TIME", "ROOM_SURCHARGE", "IMAX")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRICING_RULE_CODE_EXISTS"));
    }

    @Test void rejectsInvalidCreates() throws Exception {
        String manager = tokenFor(UserRole.MANAGER);
        for (Map<String, Object> body : List.of(newRule("NEW_RULE", "BASE_PRICE", "IMAX"), newRule("NEW_RULE", "SEAT_SURCHARGE", "GOLD"),
                newRule("NEW_RULE", "ROOM_SURCHARGE", "WEEKEND_OR_AFTER_18H"), newRule("NEW_RULE", "TIME_SURCHARGE", "ANY"))) {
            send(post(URL), manager, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PRICING_RULE_INVALID"));
        }
        List<Map<String, Object>> malformed = new ArrayList<>();
        Map<String, Object> m;
        malformed.add(newRule("bad code", "SEAT_SURCHARGE", "VIP"));
        malformed.add(newRule("AB", "SEAT_SURCHARGE", "VIP"));
        malformed.add(newRule("NEW_RULE", "FOO", "VIP"));
        m = newRule("NEW_RULE", "SEAT_SURCHARGE", "VIP"); m.put("amount", -5); malformed.add(m);
        m = newRule("NEW_RULE", "SEAT_SURCHARGE", "VIP"); m.remove("amount"); malformed.add(m);
        m = newRule("NEW_RULE", "SEAT_SURCHARGE", "VIP"); m.put("active", true); malformed.add(m);
        for (Map<String, Object> body : malformed) send(post(URL), manager, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        send(post(URL), manager, newRule("NEW_RULE", "SEAT_SURCHARGE", "VIP")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRICING_RULE_EXISTS"));
        assertThat(jdbc.queryForObject("select count(*) from pricing_rules", Integer.class)).isEqualTo(8);
    }

    @Test void databaseRejectsNegativeAmounts() {
        assertThatThrownBy(() -> jdbc.update("update pricing_rules set amount=-1 where rule_code='SUR_VIP'")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void databaseRejectsDuplicateRuleCodes() {
        assertThatThrownBy(() -> jdbc.update("insert into pricing_rules(rule_code,rule_type,target_ref,amount) values('SUR_VIP','SEAT_SURCHARGE','OTHER',1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void onlyManagerCanReadOrChangePricingRules() throws Exception {
        int id = idOf("SUR_VIP");
        send(get(URL), null, null).andExpect(status().isUnauthorized());
        send(patch(URL + "/" + id), null, Map.of("amount", 1)).andExpect(status().isUnauthorized());
        for (UserRole role : List.of(UserRole.STAFF, UserRole.CUSTOMER)) {
            String token = tokenFor(role);
            send(get(URL), token, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            send(get(URL + "/" + id), token, null).andExpect(status().isForbidden());
            send(post(URL), token, newRule("NEW_RULE", "SEAT_SURCHARGE", "VIP")).andExpect(status().isForbidden());
            send(patch(URL + "/" + id), token, Map.of("amount", 1)).andExpect(status().isForbidden());
        }
        assertThat(amountOf("SUR_VIP")).isEqualByComparingTo("15000");
    }
}
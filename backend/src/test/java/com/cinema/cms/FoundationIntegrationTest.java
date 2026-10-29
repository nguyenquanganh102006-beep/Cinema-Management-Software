package com.cinema.cms;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.UserRepository;
import com.cinema.cms.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.web.bind.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.jwt.secret=" + FoundationIntegrationTest.SECRET,
        "app.jwt.ttl-seconds=1800", "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@AutoConfigureMockMvc
@Import(FoundationIntegrationTest.TestEndpoints.class)
class FoundationIntegrationTest {
    static final String SECRET = "foundation-test-only-secret-at-least-32-bytes-long";
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
    @Autowired Flyway flyway;
    @Autowired Clock clock;

    private User create(UserRole role) {
        String suffix = UUID.randomUUID().toString().substring(0,8);
        return users.saveAndFlush(new User("u_" + suffix, "Test User", suffix + "@cinema.test",
                "09" + String.format("%010d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 10000000000L)), passwords.encode("TestPass123!"), role));
    }
    private ResultActions call(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }
    private String login(String identity, String password) throws Exception {
        String content = call("/api/v1/auth/login", Map.of("username", identity, "password", password))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(content).path("data").path("accessToken").asText();
    }
    private Map<String,String> registration(String suffix) {
        return new HashMap<>(Map.of("username", "reg_"+suffix, "fullName", "Nguyễn Khách", "email",suffix+"@example.test",
                "phone","+84" + String.format("%010d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 10000000000L)), "password", "TestPass123!"));
    }
    @Test void migrationIsRepeatableAndPricingMatchesBusinessRules() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select amount from pricing_rules where rule_code='SUR_IMAX'", Integer.class)).isEqualTo(40000);
        assertThat(jdbc.queryForObject("select amount from pricing_rules where rule_code='SUR_SWEETBOX'", Integer.class)).isEqualTo(15000);
        assertThat(jdbc.queryForObject("select count(*) from pricing_rules where rule_type='TIME_SURCHARGE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_name='users' and column_name='created_at'",String.class)).isEqualTo("timestamp with time zone");
    }
    @Test void registersOnlyCustomerNormalizesAndHashes() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        var body=registration(suffix); body.put("username", "  REG_"+suffix.toUpperCase()+"  "); body.put("email", "  "+suffix.toUpperCase()+"@EXAMPLE.TEST  ");
        String response=call("/api/v1/auth/register",body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("CUSTOMER")).andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("TestPass123!", "managerPinHash");
        User saved=users.findByUsernameOrEmail("reg_"+suffix,"reg_"+suffix).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(passwords.matches("TestPass123!",saved.getPasswordHash())).isTrue();
        call("/api/v1/auth/register",body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }
    @Test void rejectsRoleInjectionMalformedJsonAndValidation() throws Exception {
        var body=registration("badrole");body.put("role","MANAGER");
        call("/api/v1/auth/register",body).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details").isMap());
        call("/api/v1/auth/register",Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.username").exists()).andExpect(jsonPath("$.timestamp").exists());
    }
    @Test void rejectsPasswordsOverBcryptByteLimit() throws Exception {
        var body=registration("long_"+UUID.randomUUID().toString().substring(0,8));body.put("password","ấ".repeat(30));
        call("/api/v1/auth/register",body).andExpect(status().isBadRequest());
    }
    @Test void loginSupportsUsernameAndEmailForAllRoles() throws Exception {
        for(UserRole role:UserRole.values()) {
            User user=create(role);
            String token=login("  "+user.getUsername().toUpperCase()+"  ","TestPass123!");
            mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value(role.name()));
            assertThat(login(user.getEmail(),"TestPass123!")).isNotBlank();
        }
    }
    @Test void wrongCredentialsDoNotRevealAccountStatus() throws Exception {
        User user=create(UserRole.STAFF);jdbc.update("update users set status='LOCKED' where user_id=?",user.getId());
        for(String identity:List.of(user.getUsername(),"nonexistent-user")) {
            call("/api/v1/auth/login",Map.of("username",identity,"password","wrong-password"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        }
        call("/api/v1/auth/login",Map.of("username",user.getUsername(),"password","TestPass123!"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        jdbc.update("update users set status='INACTIVE' where user_id=?",user.getId());
        call("/api/v1/auth/login",Map.of("username",user.getUsername(),"password","TestPass123!"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
    }
    @Test void invalidExpiredAndMissingTokensAre401() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        for(String token:List.of("not-a-token",expiredToken())) {
            mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+expiredToken()))
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_EXPIRED"));
        User user=create(UserRole.CUSTOMER); String token=jwt.issue(user);
        String[] parts=token.split("\\.");
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+parts[0]+"."+parts[1]+"x."+parts[2]))
                .andExpect(status().isUnauthorized());
    }
    private String expiredToken() {
        return Jwts.builder().issuer("cinema-management").subject("1").id(UUID.randomUUID().toString())
                .claim("sv",0).issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
    @Test void rbacChecksRealTokensAndCurrentAccountState() throws Exception {
        for(UserRole role:UserRole.values()) {
            User user=create(role);String token=jwt.issue(user);
            mvc.perform(get("/api/v1/test/manager").header("Authorization","Bearer "+token))
                    .andExpect(status().is(role==UserRole.MANAGER?200:403));
        }
        User user=create(UserRole.MANAGER);String token=jwt.issue(user);
        jdbc.update("update users set role='CUSTOMER' where user_id=?",user.getId());
        mvc.perform(get("/api/v1/test/manager").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        jdbc.update("update users set status='LOCKED' where user_id=?",user.getId());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void securityVersionInvalidatesOldTokens() throws Exception {
        User user=create(UserRole.CUSTOMER);String token=jwt.issue(user);
        jdbc.update("update users set security_version=security_version+1 where user_id=?",user.getId());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void logoutPersistsRevocationAndPreservesOtherSession() throws Exception {
        User user=create(UserRole.CUSTOMER);String first=jwt.issue(user), second=jwt.issue(user);
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+first)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+first)).andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+second)).andExpect(status().isOk());
        assertThat(new TokenRevocationService(jdbc,clock).isRevoked(jwt.parse(first).getId())).isTrue();
    }
    @Test void cleanupOnlyDeletesExpiredRevocations() {
        var service=new TokenRevocationService(jdbc,clock);
        String expired=UUID.randomUUID().toString(),live=UUID.randomUUID().toString();
        service.revoke(expired,Instant.now().minusSeconds(10));service.revoke(live,Instant.now().plusSeconds(300));
        service.cleanup();assertThat(service.isRevoked(expired)).isFalse();assertThat(service.isRevoked(live)).isTrue();
    }
    @Test void concurrentRegistrationCannotDuplicateIdentity() throws Exception {
        var body=registration("race_"+UUID.randomUUID().toString().substring(0,8));
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            var start=new CountDownLatch(1);
            Callable<Integer> action=()->{start.await();return call("/api/v1/auth/register",body).andReturn().getResponse().getStatus();};
            var a=executor.submit(action);var b=executor.submit(action);start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        } finally {executor.shutdownNow();}
    }
    @Test void errorsAndSwaggerHaveExpectedContract() throws Exception {
        User user=create(UserRole.CUSTOMER);
        mvc.perform(get("/api/v1/test/manager").header("Authorization","Bearer "+jwt.issue(user)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.path").value("/api/v1/test/manager")).andExpect(jsonPath("$.details").isMap());
        mvc.perform(get("/api/v1/test/failure").header("Authorization","Bearer "+jwt.issue(user)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-value"))));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
    @Test void corsAllowsConfiguredFrontendButRejectsOtherOrigins() throws Exception {
        for(int port:List.of(3001,3002,3003)) {
            mvc.perform(options("/api/v1/auth/login").header("Origin","http://localhost:"+port).header("Access-Control-Request-Method","POST"))
                    .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://localhost:"+port));
        }
        mvc.perform(options("/api/v1/auth/login").header("Origin","https://untrusted.example").header("Access-Control-Request-Method","POST"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORS_ORIGIN_DENIED"));
    }
    @Test void frameworkErrorsPreserveHttpStatusHeadersAndErrorContract() throws Exception {
        String token=jwt.issue(create(UserRole.CUSTOMER));
        mvc.perform(get("/api/v1/auth/login").header("Authorization","Bearer "+token))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow",org.hamcrest.Matchers.containsString("POST")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/auth/me").header("Authorization","Bearer "+token))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.status").value(405));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token).accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }
    @Test void openApiDeclaresErrorSchemasAndJsonSuccess() throws Exception {
        JsonNode api=mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for(String path:List.of("/api/v1/auth/register","/api/v1/auth/login","/api/v1/auth/me","/api/v1/auth/logout","/api/v1/auth/transfers","/api/v1/auth/transfers/consume")) {
            JsonNode operation=api.path("paths").path(path).path(path.endsWith("/me")?"get":"post");
            for(String status:List.of("400","401","403","405","415","500","503")) {
                assertThat(operation.path("responses").path(status).path("content").path("application/json").path("schema").path("$ref").asText()).isEqualTo("#/components/schemas/ApiErrorResponse");
            }
        }
        assertThat(api.at("/paths/~1api~1v1~1auth~1register/post/responses/409/content/application~1json/schema/$ref").asText()).isEqualTo("#/components/schemas/ApiErrorResponse");
        assertThat(api.at("/paths/~1api~1v1~1auth~1login/post/responses/200/content/application~1json/schema/$ref").asText()).isEqualTo("#/components/schemas/ApiResponseLoginResponse");
        assertThat(api.at("/paths/~1api~1v1~1auth~1logout/post/responses/204").has("content")).isFalse();
    }
    private String transfer(String token) throws Exception {
        JsonNode result=mapper.readTree(mvc.perform(post("/api/v1/auth/transfers").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String url=result.path("data").path("redirectUrl").asText();
        assertThat(url).doesNotContain(token);
        return url.substring(url.indexOf("#auth-transfer=")+15);
    }
    @Test void transferRoutesByRoleRevokesSourceAndAllowsOnlyOneExchange() throws Exception {
        for(UserRole role:UserRole.values()) {
            User user=create(role);String source=jwt.issue(user),independent=jwt.issue(user);
            String code=transfer(source);
            mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+source)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+independent)).andExpect(status().isOk());
            JsonNode response=mapper.readTree(call("/api/v1/auth/transfers/consume",Map.of("code",code,"role",role.name()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.role").value(role.name())).andReturn().getResponse().getContentAsString());
            String target=response.path("data").path("accessToken").asText();
            mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+target)).andExpect(status().isOk());
            call("/api/v1/auth/transfers/consume",Map.of("code",code,"role",role.name())).andExpect(status().isUnauthorized());
            assertThat(jdbc.queryForObject("select count(*) from auth_transfers where code_hash=?",Integer.class,code)).isZero();
        }
    }
    @Test void transferRejectsWrongRoleExpiredCodeAndChangedAccount() throws Exception {
        User user=create(UserRole.MANAGER);String code=transfer(jwt.issue(user));
        call("/api/v1/auth/transfers/consume",Map.of("code",code,"role","STAFF")).andExpect(status().isUnauthorized());
        jdbc.update("update users set status='LOCKED' where user_id=?",user.getId());
        call("/api/v1/auth/transfers/consume",Map.of("code",code,"role","MANAGER")).andExpect(status().isUnauthorized());
        jdbc.update("update users set status='ACTIVE' where user_id=?",user.getId());
        jdbc.update("update auth_transfers set expires_at=current_timestamp-interval '1 minute' where user_id=?",user.getId());
        call("/api/v1/auth/transfers/consume",Map.of("code",code,"role","MANAGER")).andExpect(status().isUnauthorized());
        call("/api/v1/auth/transfers/consume",Map.of("code","a".repeat(64),"role","MANAGER")).andExpect(status().isUnauthorized());
    }
    @Test void concurrentTransferExchangeHasExactlyOneWinner() throws Exception {
        String code=transfer(jwt.issue(create(UserRole.STAFF)));
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            var start=new CountDownLatch(1);
            Callable<Integer> action=()->{start.await();return call("/api/v1/auth/transfers/consume",Map.of("code",code,"role","STAFF")).andReturn().getResponse().getStatus();};
            var a=executor.submit(action);var b=executor.submit(action);start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,401);
        } finally {executor.shutdownNow();}
    }
    @TestConfiguration static class TestEndpoints {
        @Bean TestController testController() {return new TestController();}
    }
    @RestController static class TestController {
        @GetMapping("/api/v1/test/manager") @PreAuthorize("hasRole('MANAGER')")
        public Map<String,String> manager() {return Map.of("ok","true");}
        @GetMapping("/api/v1/test/failure") public void fail() {throw new IllegalStateException("private-value");}
    }
}

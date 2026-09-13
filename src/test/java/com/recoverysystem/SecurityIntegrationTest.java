package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(SecurityIntegrationTest.SecurityProbeTestConfiguration.class)
class SecurityIntegrationTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final String VALID_PASSWORD = "Strong1!password";
    private static final String SESSION_COOKIE_NAME = "SESSION";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearAuthenticationData() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION");
        userRepository.deleteAll();
    }

    @Test
    void unauthenticatedProbeRequestIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/test/probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationSessionAuthenticatesALaterProbeRequest() throws Exception {
        MvcResult registration = register(uniqueEmail("register-session"), VALID_PASSWORD)
                .andExpect(status().isCreated())
                .andReturn();

        mockMvc.perform(get("/api/test/probe")
                        .cookie(requireSessionCookie(registration)))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void loginSessionAuthenticatesALaterProbeRequest() throws Exception {
        User user = saveUser(uniqueEmail("login-session"));
        MvcResult login = login(user.getEmail(), VALID_PASSWORD)
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(get("/api/test/probe")
                        .cookie(requireSessionCookie(login)))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void logoutInvalidatesAccessForTheSameSessionCookie() throws Exception {
        User user = saveUser(uniqueEmail("logout-session"));
        MvcResult login = login(user.getEmail(), VALID_PASSWORD)
                .andExpect(status().isOk())
                .andReturn();
        Cookie sessionCookie = requireSessionCookie(login);

        mockMvc.perform(post("/api/auth/logout")
                        .cookie(sessionCookie)
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/test/probe")
                        .cookie(sessionCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginRotatesExistingSessionIdentifier() throws Exception {
        User user = saveUser(uniqueEmail("session-fixation"));
        MvcResult unauthenticatedProbe = mockMvc.perform(get("/api/test/probe"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        Cookie beforeAuthentication = requireSessionCookie(unauthenticatedProbe);

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .cookie(beforeAuthentication)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(user.getEmail(), VALID_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie afterAuthentication = requireSessionCookie(login);
        String beforeSessionId = decodedSessionId(beforeAuthentication);
        String afterSessionId = decodedSessionId(afterAuthentication);

        assertNotEquals(beforeSessionId, afterSessionId);
        System.out.println("Session fixation rotation: before=" + beforeSessionId
                + "; after=" + afterSessionId);
    }

    private ResultActions register(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(email, password)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(email, password)));
    }

    private String credentialsJson(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("email", email, "password", password));
    }

    private User saveUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(VALID_PASSWORD));
        user.setRole(UserRole.PATIENT);
        return userRepository.saveAndFlush(user);
    }

    private Cookie requireSessionCookie(MvcResult result) {
        Cookie sessionCookie = result.getResponse().getCookie(SESSION_COOKIE_NAME);
        assertNotNull(sessionCookie);
        return sessionCookie;
    }

    private String decodedSessionId(Cookie sessionCookie) {
        return new String(
                Base64.getDecoder().decode(sessionCookie.getValue()),
                StandardCharsets.UTF_8);
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + SEQUENCE.incrementAndGet() + "@example.com";
    }

    // Test-only probe: it proves authentication/session/filter behavior in isolation and
    // does not represent or prove authorization for any production business operation.
    @RestController
    static class SecurityProbeController {

        @GetMapping("/api/test/probe")
        String probe() {
            return "ok";
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityProbeTestConfiguration {

        @Bean
        SecurityProbeController securityProbeController() {
            return new SecurityProbeController();
        }

        // This higher-priority chain applies only in SecurityIntegrationTest. It never enters
        // the production application context and leaves the production filter chain unchanged.
        @Bean
        @Order(0)
        SecurityFilterChain securityProbeFilterChain(HttpSecurity http) throws Exception {
            http.securityMatcher("/api/test/**", "/api/auth/**")
                    .csrf(csrf -> csrf.csrfTokenRepository(
                            CookieCsrfTokenRepository.withHttpOnlyFalse()))
                    .sessionManagement(session -> session
                            .sessionFixation(fixation -> fixation.changeSessionId())
                            .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                    .authorizeHttpRequests(authorize -> authorize
                            .requestMatchers("/api/auth/**").permitAll()
                            .requestMatchers("/api/test/probe").authenticated()
                            .anyRequest().denyAll())
                    .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
            return http.build();
        }
    }
}

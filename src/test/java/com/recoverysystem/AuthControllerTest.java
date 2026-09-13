package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.security.AuthenticatedUser;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthControllerTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final String VALID_PASSWORD = "Strong1!password";
    private static final String SESSION_COOKIE_NAME = "SESSION";
    private static final String INVALID_CREDENTIALS_RESPONSE =
            "{\"message\":\"Invalid email or password\"}";

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

    @Autowired
    private JdbcIndexedSessionRepository sessionRepository;

    @BeforeEach
    void clearAuthenticationData() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION");
        userRepository.deleteAll();
    }

    @Test
    void registrationCreatesPatientAndIgnoresForgedRole() throws Exception {
        String email = uniqueEmail("register");
        ResultActions registration = mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", VALID_PASSWORD,
                                "role", "ADMIN"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value(UserRole.PATIENT.name()));

        User savedUser = userRepository.findByEmail(email).orElseThrow();
        registration.andExpect(jsonPath("$.userId").value(savedUser.getId()));
        assertEquals(UserRole.PATIENT, savedUser.getRole());
    }

    @Test
    void registrationPersistsAuthenticatedSecurityContextInSession() throws Exception {
        String email = uniqueEmail("register-session");

        MvcResult registration = register(email, VALID_PASSWORD)
                .andExpect(status().isCreated())
                .andReturn();

        Cookie sessionCookie = requireSessionCookie(registration);
        Session persistedSession = sessionRepository.findById(repositorySessionId(sessionCookie));
        assertNotNull(persistedSession);
        SecurityContext securityContext = persistedSession.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertNotNull(securityContext);
        Authentication authentication = securityContext.getAuthentication();
        assertNotNull(authentication);
        assertTrue(authentication.isAuthenticated());
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        assertEquals(userRepository.findByEmail(email).orElseThrow().getId(), principal.getUserId());
    }

    @Test
    void registrationRejectsPasswordsThatViolateTheStrongPasswordPolicy() throws Exception {
        String[] weakPasswords = {
            "WEAK1!PASSWORD",
            "weak1!password",
            "Weak!!password",
            "Weak12password",
            "Aa1!aaa",
            "Aa1!" + "a".repeat(125)
        };

        for (String weakPassword : weakPasswords) {
            register(uniqueEmail("weak"), weakPassword)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.password").exists());
        }
        assertEquals(0L, userRepository.count());
    }

    @Test
    void duplicateRegistrationReturnsConflict() throws Exception {
        User existingUser = saveUser(uniqueEmail("duplicate"), UserRole.PATIENT);

        register(existingUser.getEmail(), VALID_PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());

        assertEquals(1L, userRepository.count());
    }

    @Test
    void registrationWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(uniqueEmail("register-no-csrf"), VALID_PASSWORD)))
                .andExpect(status().isForbidden());

        assertEquals(0L, userRepository.count());
    }

    @Test
    void loginWithCorrectCredentialsReturnsAuthenticatedUserId() throws Exception {
        User user = saveUser(uniqueEmail("login"), UserRole.RECEPTIONIST);

        login(user.getEmail(), VALID_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId()))
                .andExpect(jsonPath("$.authenticated").value(true));
    }

    @Test
    void loginWithWrongPasswordReturnsGenericUnauthorizedResponse() throws Exception {
        User user = saveUser(uniqueEmail("wrong-password"), UserRole.PATIENT);

        login(user.getEmail(), "Wrong1!password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void nonexistentEmailIsIndistinguishableFromWrongPassword() throws Exception {
        User user = saveUser(uniqueEmail("indistinguishable"), UserRole.PATIENT);

        MvcResult wrongPassword = login(user.getEmail(), "Wrong1!password")
                .andExpect(status().isUnauthorized())
                .andReturn();
        MvcResult nonexistentEmail = login(uniqueEmail("missing"), VALID_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertEquals(wrongPassword.getResponse().getStatus(),
                nonexistentEmail.getResponse().getStatus());
        assertEquals(INVALID_CREDENTIALS_RESPONSE,
                wrongPassword.getResponse().getContentAsString());
        assertEquals(wrongPassword.getResponse().getContentAsString(),
                nonexistentEmail.getResponse().getContentAsString());
    }

    @Test
    void loginWithoutCsrfTokenIsForbidden() throws Exception {
        User user = saveUser(uniqueEmail("login-no-csrf"), UserRole.PATIENT);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(user.getEmail(), VALID_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutInvalidatesSessionAndRemovesSecurityContext() throws Exception {
        User user = saveUser(uniqueEmail("logout"), UserRole.PATIENT);
        MvcResult login = login(user.getEmail(), VALID_PASSWORD)
                .andExpect(status().isOk())
                .andReturn();
        Cookie sessionCookie = requireSessionCookie(login);
        String sessionId = repositorySessionId(sessionCookie);
        assertNotNull(sessionRepository.findById(sessionId));

        mockMvc.perform(post("/api/auth/logout")
                        .cookie(sessionCookie)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("logged out"));

        assertNull(sessionRepository.findById(sessionId));
    }

    @Test
    void logoutWithoutActiveSessionIsHarmless() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("logged out"));
    }

    @Test
    void registrationStoresBcryptHashInsteadOfRawPassword() throws Exception {
        String email = uniqueEmail("bcrypt");

        register(email, VALID_PASSWORD)
                .andExpect(status().isCreated());

        String storedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?",
                String.class,
                email);
        assertNotNull(storedHash);
        assertTrue(storedHash.startsWith("$2"));
        assertNotEquals(VALID_PASSWORD, storedHash);
        assertTrue(passwordEncoder.matches(VALID_PASSWORD, storedHash));
        assertFalse(passwordEncoder.matches("Wrong1!password", storedHash));
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

    private User saveUser(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(VALID_PASSWORD));
        user.setRole(role);
        return userRepository.saveAndFlush(user);
    }

    private Cookie requireSessionCookie(MvcResult result) {
        Cookie sessionCookie = result.getResponse().getCookie(SESSION_COOKIE_NAME);
        assertNotNull(sessionCookie);
        return sessionCookie;
    }

    private String repositorySessionId(Cookie sessionCookie) {
        return new String(
                Base64.getDecoder().decode(sessionCookie.getValue()),
                StandardCharsets.UTF_8);
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + SEQUENCE.incrementAndGet() + "@example.com";
    }
}

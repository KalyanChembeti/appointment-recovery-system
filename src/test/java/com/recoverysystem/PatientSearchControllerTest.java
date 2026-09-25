package com.recoverysystem;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.security.AuthenticatedUser;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PatientSearchControllerTest {

    private static final int SEARCH_RESULT_LIMIT = 20;
    private static final AtomicLong SEQUENCE = new AtomicLong();

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
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearWorkflowData() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_log, slot_offer, recovery_job, "
                + "waitlist_entry, appointment, provider_schedule, provider_unavailability, "
                + "provider, appointment_type, specialty, users RESTART IDENTITY CASCADE");
    }

    @Test
    void partialEmailSearchIsCaseInsensitive() throws Exception {
        User patient = saveUser(
                UserRole.PATIENT,
                "ava.morgan@example.com",
                "Different Name");
        saveUser(UserRole.PATIENT, "unrelated@example.com", "Other Patient");
        User receptionist = saveUser(
                UserRole.RECEPTIONIST,
                uniqueEmail("receptionist"),
                "Receptionist");

        performSearch(receptionist, "MORGAN@EX")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(patient.getId()))
                .andExpect(jsonPath("$[0].email").value(patient.getEmail()))
                .andExpect(jsonPath("$[0].displayName").value("Different Name"));
    }

    @Test
    void partialDisplayNameSearchIsCaseInsensitive() throws Exception {
        User patient = saveUser(
                UserRole.PATIENT,
                "display-search@example.com",
                "Elena Margot Stone");
        User receptionist = saveUser(
                UserRole.RECEPTIONIST,
                uniqueEmail("receptionist"),
                "Receptionist");

        performSearch(receptionist, "mArGoT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(patient.getId()))
                .andExpect(jsonPath("$[0].displayName").value("Elena Margot Stone"));
    }

    @Test
    void literalPercentInQueryIsNotTreatedAsWildcard() throws Exception {
        User literalMatch = saveUser(
                UserRole.PATIENT,
                "literal-percent@example.com",
                "100% Ready");
        saveUser(
                UserRole.PATIENT,
                "wildcard-control@example.com",
                "100 Percent Ready");
        User receptionist = saveUser(
                UserRole.RECEPTIONIST,
                uniqueEmail("receptionist"),
                "Receptionist");

        performSearch(receptionist, "100%")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(literalMatch.getId()))
                .andExpect(jsonPath("$[0].displayName").value("100% Ready"));
    }

    @Test
    void queryShorterThanTwoCharactersIsBadRequest() throws Exception {
        User receptionist = saveUser(
                UserRole.RECEPTIONIST,
                uniqueEmail("receptionist"),
                "Receptionist");

        performSearch(receptionist, "a")
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchReturnsOnlyPatientRoleUsers() throws Exception {
        User patient = saveUser(
                UserRole.PATIENT,
                "shared-pattern-patient@example.com",
                "Shared Pattern Patient");
        saveUser(
                UserRole.RECEPTIONIST,
                "shared-pattern-receptionist@example.com",
                "Shared Pattern Receptionist");
        saveUser(
                UserRole.PROVIDER,
                "shared-pattern-provider@example.com",
                "Shared Pattern Provider");
        User admin = saveUser(UserRole.ADMIN, uniqueEmail("admin"), "Administrator");

        performSearch(admin, "SHARED-PATTERN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(patient.getId()));
    }

    @Test
    void nullDisplayNameIsPreservedAsJsonNull() throws Exception {
        User patient = saveUser(
                UserRole.PATIENT,
                "null-display-patient@example.com",
                null);
        User receptionist = saveUser(
                UserRole.RECEPTIONIST,
                uniqueEmail("receptionist"),
                "Receptionist");

        performSearch(receptionist, "NULL-DISPLAY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(patient.getId()))
                .andExpect(jsonPath("$[0].displayName").value(nullValue()));
    }

    @Test
    void patientAndProviderCannotSearchPatients() throws Exception {
        User patient = saveUser(UserRole.PATIENT, uniqueEmail("patient"), "Patient");
        User provider = saveUser(UserRole.PROVIDER, uniqueEmail("provider"), "Provider");

        performSearch(patient, "match")
                .andExpect(status().isForbidden());
        performSearch(provider, "match")
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedPatientSearchIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/patients").param("query", "match"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patientSearchResultsAreCapped() throws Exception {
        for (int index = 0; index < SEARCH_RESULT_LIMIT + 5; index++) {
            saveUser(
                    UserRole.PATIENT,
                    "cap-match-%02d@example.com".formatted(index),
                    "Cap Match %02d".formatted(index));
        }
        User admin = saveUser(UserRole.ADMIN, uniqueEmail("admin"), "Administrator");

        performSearch(admin, "CAP-MATCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(SEARCH_RESULT_LIMIT))
                .andExpect(jsonPath("$[0].email").value("cap-match-00@example.com"))
                .andExpect(jsonPath("$[19].email").value("cap-match-19@example.com"));
    }

    private ResultActions performSearch(User caller, String query) throws Exception {
        return mockMvc.perform(get("/api/patients")
                .with(user(new AuthenticatedUser(caller)))
                .param("query", query));
    }

    private User saveUser(UserRole role, String email, String displayName) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName(displayName);
        return userRepository.saveAndFlush(user);
    }

    private String uniqueEmail(String prefix) {
        return "patient-search-" + prefix + "-" + SEQUENCE.incrementAndGet()
                + "@example.com";
    }
}

package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.Specialty;
import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.web.dto.CreateProviderRequest;
import com.recoverysystem.web.dto.ProviderResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
class ProviderControllerTest {

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
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SpecialtyRepository specialtyRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearWorkflowData() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_log, slot_offer, recovery_job, "
                + "waitlist_entry, appointment, provider_schedule, provider_unavailability, "
                + "provider, appointment_type, specialty, users RESTART IDENTITY CASCADE");
    }

    @Test
    void adminCreatesProviderForExistingProviderUser() throws Exception {
        Specialty specialty = saveSpecialty();
        User providerUser = saveUser(UserRole.PROVIDER, "Provider");
        User admin = saveUser(UserRole.ADMIN, "Administrator");
        CreateProviderRequest request = new CreateProviderRequest(
                providerUser.getId(),
                specialty.getId(),
                "LICENSE-1001",
                "Board certified");

        MvcResult result = performCreate(admin, request, true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(providerUser.getId()))
                .andExpect(jsonPath("$.specialtyId").value(specialty.getId()))
                .andExpect(jsonPath("$.licenseNumber").value("LICENSE-1001"))
                .andExpect(jsonPath("$.qualifications").value("Board certified"))
                .andReturn();

        ProviderResponse response = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), ProviderResponse.class);
        Provider persisted = providerRepository.findByUserId(providerUser.getId()).orElseThrow();
        assertEquals(persisted.getId(), response.id());
        assertEquals(providerUser.getId(), persisted.getUserId());
        assertEquals(specialty.getId(), persisted.getSpecialtyId());
        assertEquals("LICENSE-1001", persisted.getLicenseNumber());
        assertEquals("Board certified", persisted.getQualifications());
    }

    @Test
    void providerCreationAuditUsesAuthenticatedAdminAsActor() throws Exception {
        Specialty specialty = saveSpecialty();
        User providerUser = saveUser(UserRole.PROVIDER, "Provider");
        User admin = saveUser(UserRole.ADMIN, "Administrator");
        assertNotEquals(providerUser.getId(), admin.getId());

        performCreate(
                        admin,
                        new CreateProviderRequest(
                                providerUser.getId(),
                                specialty.getId(),
                                "LICENSE-1002",
                                null),
                        true)
                .andExpect(status().isCreated());

        Provider provider = providerRepository.findByUserId(providerUser.getId()).orElseThrow();
        List<AuditLog> audits = auditLogRepository.findAll();
        assertEquals(1, audits.size());
        AuditLog audit = audits.getFirst();
        assertEquals("Provider", audit.getEntityType());
        assertEquals(provider.getId(), audit.getEntityId());
        assertEquals("CREATE", audit.getAction());
        assertEquals(ActorType.USER, audit.getActorType());
        assertEquals(admin.getId(), audit.getActorUserId());
        assertNotEquals(providerUser.getId(), audit.getActorUserId());
    }

    @Test
    void missingReferencedUserIsNotFound() throws Exception {
        Specialty specialty = saveSpecialty();
        User admin = saveUser(UserRole.ADMIN, "Administrator");

        performCreate(
                        admin,
                        new CreateProviderRequest(
                                9_999_999L,
                                specialty.getId(),
                                "LICENSE-1003",
                                null),
                        true)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        assertEquals(0L, providerRepository.count());
        assertEquals(0L, auditLogRepository.count());
    }

    @Test
    void nonProviderUserRoleIsConflict() throws Exception {
        Specialty specialty = saveSpecialty();
        User patient = saveUser(UserRole.PATIENT, "Patient");
        User admin = saveUser(UserRole.ADMIN, "Administrator");

        performCreate(
                        admin,
                        new CreateProviderRequest(
                                patient.getId(),
                                specialty.getId(),
                                "LICENSE-1004",
                                null),
                        true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_USER_ROLE"));

        assertEquals(0L, providerRepository.count());
        assertEquals(0L, auditLogRepository.count());
    }

    @Test
    void userAlreadyLinkedToProviderIsConflict() throws Exception {
        Specialty specialty = saveSpecialty();
        User providerUser = saveUser(UserRole.PROVIDER, "Provider");
        saveProvider(providerUser, specialty, "EXISTING-LICENSE");
        User admin = saveUser(UserRole.ADMIN, "Administrator");

        performCreate(
                        admin,
                        new CreateProviderRequest(
                                providerUser.getId(),
                                specialty.getId(),
                                "LICENSE-1005",
                                null),
                        true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("USER_ALREADY_LINKED_TO_PROVIDER"));

        assertEquals(1L, providerRepository.count());
        assertEquals(0L, auditLogRepository.count());
    }

    @Test
    void missingReferencedSpecialtyIsNotFound() throws Exception {
        User providerUser = saveUser(UserRole.PROVIDER, "Provider");
        User admin = saveUser(UserRole.ADMIN, "Administrator");

        performCreate(
                        admin,
                        new CreateProviderRequest(
                                providerUser.getId(),
                                9_999_999L,
                                "LICENSE-1006",
                                null),
                        true)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SPECIALTY_NOT_FOUND"));

        assertEquals(0L, providerRepository.count());
        assertEquals(0L, auditLogRepository.count());
    }

    @Test
    void receptionistCannotCreateProvider() throws Exception {
        Specialty specialty = saveSpecialty();
        User providerUser = saveUser(UserRole.PROVIDER, "Provider");
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");

        performCreate(
                        receptionist,
                        new CreateProviderRequest(
                                providerUser.getId(),
                                specialty.getId(),
                                "LICENSE-1007",
                                null),
                        true)
                .andExpect(status().isForbidden());

        assertEquals(0L, providerRepository.count());
    }

    @Test
    void patientCannotCreateProvider() throws Exception {
        Specialty specialty = saveSpecialty();
        User providerUser = saveUser(UserRole.PROVIDER, "Provider");
        User patient = saveUser(UserRole.PATIENT, "Patient");

        performCreate(
                        patient,
                        new CreateProviderRequest(
                                providerUser.getId(),
                                specialty.getId(),
                                "LICENSE-1008",
                                null),
                        true)
                .andExpect(status().isForbidden());

        assertEquals(0L, providerRepository.count());
    }

    @Test
    void providerCannotCreateProvider() throws Exception {
        Specialty specialty = saveSpecialty();
        User providerUser = saveUser(UserRole.PROVIDER, "Provider");

        performCreate(
                        providerUser,
                        new CreateProviderRequest(
                                providerUser.getId(),
                                specialty.getId(),
                                "LICENSE-1009",
                                null),
                        true)
                .andExpect(status().isForbidden());

        assertEquals(0L, providerRepository.count());
    }

    @Test
    void unauthenticatedProviderCreationIsUnauthorized() throws Exception {
        Specialty specialty = saveSpecialty();
        User providerUser = saveUser(UserRole.PROVIDER, "Provider");

        mockMvc.perform(post("/api/providers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProviderRequest(
                                providerUser.getId(),
                                specialty.getId(),
                                "LICENSE-1010",
                                null))))
                .andExpect(status().isUnauthorized());

        assertEquals(0L, providerRepository.count());
    }

    @Test
    void providerCreationRequiresCsrfToken() throws Exception {
        Specialty specialty = saveSpecialty();
        User providerUser = saveUser(UserRole.PROVIDER, "Provider");
        User admin = saveUser(UserRole.ADMIN, "Administrator");

        performCreate(
                        admin,
                        new CreateProviderRequest(
                                providerUser.getId(),
                                specialty.getId(),
                                "LICENSE-1011",
                                null),
                        false)
                .andExpect(status().isForbidden());

        assertEquals(0L, providerRepository.count());
    }

    private ResultActions performCreate(
            User caller, CreateProviderRequest requestBody, boolean includeCsrf)
            throws Exception {
        var request = post("/api/providers")
                .with(user(new AuthenticatedUser(caller)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody));
        if (includeCsrf) {
            request.with(csrf());
        }
        return mockMvc.perform(request);
    }

    private Specialty saveSpecialty() {
        long number = SEQUENCE.incrementAndGet();
        Specialty specialty = new Specialty();
        specialty.setName("Provider API Specialty " + number);
        return specialtyRepository.saveAndFlush(specialty);
    }

    private Provider saveProvider(User user, Specialty specialty, String licenseNumber) {
        Provider provider = new Provider();
        provider.setUserId(user.getId());
        provider.setSpecialtyId(specialty.getId());
        provider.setLicenseNumber(licenseNumber);
        return providerRepository.saveAndFlush(provider);
    }

    private User saveUser(UserRole role, String displayName) {
        long number = SEQUENCE.incrementAndGet();
        User user = new User();
        user.setEmail("provider-api-" + role.name().toLowerCase()
                + "-" + number + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName(displayName);
        return userRepository.saveAndFlush(user);
    }
}

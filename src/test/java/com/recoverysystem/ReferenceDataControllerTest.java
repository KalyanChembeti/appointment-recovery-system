package com.recoverysystem;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.Specialty;
import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.service.ProviderCreationService;
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
class ReferenceDataControllerTest {

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
    private SpecialtyRepository specialtyRepository;

    @Autowired
    private AppointmentTypeRepository appointmentTypeRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ProviderCreationService providerCreationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearWorkflowData() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_log, slot_offer, recovery_job, "
                + "waitlist_entry, appointment, provider_schedule, provider_unavailability, "
                + "provider, appointment_type, specialty, users RESTART IDENTITY CASCADE");
    }

    @Test
    void specialtyListingReturnsExpectedEntities() throws Exception {
        Specialty cardiology = saveSpecialty("Cardiology", "Heart and vascular care");
        Specialty neurology = saveSpecialty("Neurology", "Brain and nervous system care");
        User caller = saveUser(UserRole.PATIENT, "Catalog Patient");

        performGet(caller, "/api/specialties")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(cardiology.getId()))
                .andExpect(jsonPath("$[0].name").value("Cardiology"))
                .andExpect(jsonPath("$[0].description").value("Heart and vascular care"))
                .andExpect(jsonPath("$[1].id").value(neurology.getId()))
                .andExpect(jsonPath("$[1].name").value("Neurology"))
                .andExpect(jsonPath("$[1].description")
                        .value("Brain and nervous system care"));
    }

    @Test
    void appointmentTypeListingReturnsOnlyActiveTypesAndFiltersBySpecialty()
            throws Exception {
        Specialty cardiology = saveSpecialty("Cardiology", null);
        Specialty neurology = saveSpecialty("Neurology", null);
        AppointmentType consultation = saveAppointmentType(
                cardiology, "Consultation", 30, true);
        AppointmentType followUp = saveAppointmentType(
                neurology, "Follow-up", 45, true);
        saveAppointmentType(cardiology, "Retired assessment", 60, false);
        User caller = saveUser(UserRole.PATIENT, "Catalog Patient");

        performGet(caller, "/api/appointment-types")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(consultation.getId()))
                .andExpect(jsonPath("$[0].durationMinutes").value(30))
                .andExpect(jsonPath("$[0].specialtyId").value(cardiology.getId()))
                .andExpect(jsonPath("$[1].id").value(followUp.getId()))
                .andExpect(jsonPath("$[1].durationMinutes").value(45))
                .andExpect(jsonPath("$[1].specialtyId").value(neurology.getId()));

        performGet(caller, "/api/appointment-types", cardiology.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(consultation.getId()));
    }

    @Test
    void providerListingReturnsJoinedDisplayNamesAndFiltersBySpecialty()
            throws Exception {
        Specialty cardiology = saveSpecialty("Cardiology", null);
        Specialty neurology = saveSpecialty("Neurology", null);
        User firstProviderUser = saveUser(UserRole.PROVIDER, "Dr. Avery Chen");
        User secondProviderUser = saveUser(UserRole.PROVIDER, "Dr. Morgan Reyes");
        Provider firstProvider = saveProvider(
                firstProviderUser, cardiology, "CATALOG-CARDIOLOGY");
        Provider secondProvider = saveProvider(
                secondProviderUser, neurology, "CATALOG-NEUROLOGY");
        User caller = saveUser(UserRole.PATIENT, "Catalog Patient");

        performGet(caller, "/api/providers")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(firstProvider.getId()))
                .andExpect(jsonPath("$[0].userId").value(firstProviderUser.getId()))
                .andExpect(jsonPath("$[0].specialtyId").value(cardiology.getId()))
                .andExpect(jsonPath("$[0].displayName").value("Dr. Avery Chen"))
                .andExpect(jsonPath("$[1].id").value(secondProvider.getId()))
                .andExpect(jsonPath("$[1].displayName").value("Dr. Morgan Reyes"));

        performGet(caller, "/api/providers", neurology.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(secondProvider.getId()))
                .andExpect(jsonPath("$[0].specialtyId").value(neurology.getId()));
    }

    @Test
    void providerListingReturnsNullDisplayNameWithoutFallback() throws Exception {
        Specialty specialty = saveSpecialty("Family Medicine", null);
        User providerUser = saveUser(UserRole.PROVIDER, null);
        User admin = saveUser(UserRole.ADMIN, "Administrator");
        Provider provider = providerCreationService.createProvider(
                providerUser.getId(),
                specialty.getId(),
                "CATALOG-NULL-DISPLAY",
                null,
                admin.getId());
        User caller = saveUser(UserRole.PATIENT, "Catalog Patient");
        long auditCountBeforeQuery = auditLogRepository.count();

        performGet(caller, "/api/providers", specialty.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(provider.getId()))
                .andExpect(jsonPath("$[0].displayName").value(nullValue()));

        assertNull(userRepository.findById(providerUser.getId()).orElseThrow().getDisplayName());
        assertEquals(auditCountBeforeQuery, auditLogRepository.count());
    }

    @Test
    void unauthenticatedReferenceDataRequestsAreUnauthorized() throws Exception {
        mockMvc.perform(get("/api/specialties"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/appointment-types"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/providers"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions performGet(User caller, String path) throws Exception {
        return mockMvc.perform(get(path)
                .with(user(new AuthenticatedUser(caller))));
    }

    private ResultActions performGet(User caller, String path, Long specialtyId)
            throws Exception {
        return mockMvc.perform(get(path)
                .with(user(new AuthenticatedUser(caller)))
                .param("specialtyId", specialtyId.toString()));
    }

    private Specialty saveSpecialty(String name, String description) {
        Specialty specialty = new Specialty();
        specialty.setName(name);
        specialty.setDescription(description);
        return specialtyRepository.saveAndFlush(specialty);
    }

    private AppointmentType saveAppointmentType(
            Specialty specialty, String name, int durationMinutes, boolean active) {
        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName(name);
        appointmentType.setDurationMinutes(durationMinutes);
        appointmentType.setSpecialtyId(specialty.getId());
        appointmentType.setActive(active);
        return appointmentTypeRepository.saveAndFlush(appointmentType);
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
        user.setEmail("reference-data-" + role.name().toLowerCase()
                + "-" + number + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName(displayName);
        return userRepository.saveAndFlush(user);
    }
}

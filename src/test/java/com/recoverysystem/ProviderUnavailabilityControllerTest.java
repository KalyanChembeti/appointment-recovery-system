package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.ProviderUnavailability;
import com.recoverysystem.domain.entity.Specialty;
import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.CancellationReason;
import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.web.dto.RequestProviderBlockRequest;
import java.time.Instant;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProviderUnavailabilityControllerTest {

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
    private AppointmentTypeRepository appointmentTypeRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private ProviderUnavailabilityRepository providerUnavailabilityRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearWorkflowData() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_log, slot_offer, recovery_job, "
                + "waitlist_entry, appointment, provider_schedule, provider_unavailability, "
                + "provider, appointment_type, specialty, users RESTART IDENTITY CASCADE");
    }

    @Test
    void providerRequestsOwnBlockWithoutConflictsAsActive() throws Exception {
        ProviderFixture fixture = createProviderFixture();
        Instant startAt = futureStart();

        performCreate(fixture.providerUser(), null, startAt, startAt.plusSeconds(3_600), true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerId").value(fixture.provider().getId()))
                .andExpect(jsonPath("$.startAt").value(startAt.toString()))
                .andExpect(jsonPath("$.endAt").value(startAt.plusSeconds(3_600).toString()))
                .andExpect(jsonPath("$.status").value(ProviderUnavailabilityStatus.ACTIVE.name()))
                .andExpect(jsonPath("$.reason").value("Conference"))
                .andExpect(jsonPath("$.conflictingAppointmentIds").isEmpty());

        ProviderUnavailability persisted =
                providerUnavailabilityRepository.findAll().getFirst();
        assertEquals(fixture.provider().getId(), persisted.getProviderId());
        assertEquals(ProviderUnavailabilityStatus.ACTIVE, persisted.getStatus());
    }

    @Test
    void providerConflictCreatesPendingBlockAndReturnsConflictingAppointmentId()
            throws Exception {
        ProviderFixture fixture = createProviderFixture();
        Instant startAt = futureStart();
        Appointment conflictingAppointment = saveScheduledAppointment(
                fixture, startAt.plusSeconds(300), startAt.plusSeconds(1_800));

        performCreate(fixture.providerUser(), null, startAt, startAt.plusSeconds(3_600), true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerId").value(fixture.provider().getId()))
                .andExpect(jsonPath("$.status").value(ProviderUnavailabilityStatus.PENDING.name()))
                .andExpect(jsonPath("$.conflictingAppointmentIds.length()").value(1))
                .andExpect(jsonPath("$.conflictingAppointmentIds[0]")
                        .value(conflictingAppointment.getId()));

        ProviderUnavailability persisted =
                providerUnavailabilityRepository.findAll().getFirst();
        assertEquals(ProviderUnavailabilityStatus.PENDING, persisted.getStatus());
        assertTrue(providerUnavailabilityRepository.existsOverlappingPendingBlock(
                fixture.provider().getId(), startAt, startAt.plusSeconds(3_600)));
    }

    @Test
    void adminRequestsBlockForSpecifiedProvider() throws Exception {
        ProviderFixture fixture = createProviderFixture();
        User admin = saveUser(UserRole.ADMIN, "Administrator");
        Instant startAt = futureStart();

        performCreate(
                        admin,
                        fixture.provider().getId(),
                        startAt,
                        startAt.plusSeconds(3_600),
                        true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerId").value(fixture.provider().getId()))
                .andExpect(jsonPath("$.status").value(ProviderUnavailabilityStatus.ACTIVE.name()));
    }

    @Test
    void adminMustSpecifyProviderId() throws Exception {
        createProviderFixture();
        User admin = saveUser(UserRole.ADMIN, "Administrator");
        Instant startAt = futureStart();

        performCreate(admin, null, startAt, startAt.plusSeconds(3_600), true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PROVIDER_ID"));

        assertEquals(0L, providerUnavailabilityRepository.count());
    }

    @Test
    void providerCannotRequestBlockForAnotherProvider() throws Exception {
        ProviderFixture caller = createProviderFixture();
        ProviderFixture target = createProviderFixture();
        Instant startAt = futureStart();

        performCreate(
                        caller.providerUser(),
                        target.provider().getId(),
                        startAt,
                        startAt.plusSeconds(3_600),
                        true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROVIDER_BLOCK_OWNERSHIP"));

        assertEquals(0L, providerUnavailabilityRepository.count());
    }

    @Test
    void receptionistCannotRequestProviderBlock() throws Exception {
        ProviderFixture fixture = createProviderFixture();
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");
        Instant startAt = futureStart();

        performCreate(
                        receptionist,
                        fixture.provider().getId(),
                        startAt,
                        startAt.plusSeconds(3_600),
                        true)
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));

        assertEquals(0L, providerUnavailabilityRepository.count());
    }

    @Test
    void patientCannotRequestProviderBlock() throws Exception {
        ProviderFixture fixture = createProviderFixture();
        User patient = saveUser(UserRole.PATIENT, "Patient");
        Instant startAt = futureStart();

        performCreate(
                        patient,
                        fixture.provider().getId(),
                        startAt,
                        startAt.plusSeconds(3_600),
                        true)
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));

        assertEquals(0L, providerUnavailabilityRepository.count());
    }

    @Test
    void adminActivatesPendingBlockAfterConflictIsResolved() throws Exception {
        ProviderFixture fixture = createProviderFixture();
        Instant startAt = futureStart();
        Appointment conflict =
                saveScheduledAppointment(fixture, startAt.plusSeconds(300), startAt.plusSeconds(900));
        ProviderUnavailability block = saveBlock(
                fixture.provider(), startAt, ProviderUnavailabilityStatus.PENDING);
        conflict.setStatus(AppointmentStatus.CANCELLED);
        conflict.setCancellationReason(CancellationReason.STAFF_CANCELLED);
        appointmentRepository.saveAndFlush(conflict);
        User admin = saveUser(UserRole.ADMIN, "Administrator");

        performAction(admin, block.getId(), "activate", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(block.getId()))
                .andExpect(jsonPath("$.status").value(ProviderUnavailabilityStatus.ACTIVE.name()))
                .andExpect(jsonPath("$.conflictingAppointmentIds").isEmpty());

        assertEquals(
                ProviderUnavailabilityStatus.ACTIVE,
                providerUnavailabilityRepository.findById(block.getId())
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void providerCannotActivatePendingBlock() throws Exception {
        ProviderFixture fixture = createProviderFixture();
        ProviderUnavailability block = saveBlock(
                fixture.provider(), futureStart(), ProviderUnavailabilityStatus.PENDING);

        performAction(fixture.providerUser(), block.getId(), "activate", true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROVIDER_ACTION_NOT_PERMITTED"));

        assertEquals(
                ProviderUnavailabilityStatus.PENDING,
                providerUnavailabilityRepository.findById(block.getId())
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void providerCancelsOwnPendingBlock() throws Exception {
        ProviderFixture fixture = createProviderFixture();
        ProviderUnavailability block = saveBlock(
                fixture.provider(), futureStart(), ProviderUnavailabilityStatus.PENDING);

        performAction(fixture.providerUser(), block.getId(), "cancel", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(block.getId()))
                .andExpect(jsonPath("$.providerId").value(fixture.provider().getId()))
                .andExpect(jsonPath("$.status").value(ProviderUnavailabilityStatus.CANCELLED.name()))
                .andExpect(jsonPath("$.conflictingAppointmentIds").isEmpty());

        assertEquals(
                ProviderUnavailabilityStatus.CANCELLED,
                providerUnavailabilityRepository.findById(block.getId())
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void providerCannotCancelAnotherProvidersPendingBlock() throws Exception {
        ProviderFixture caller = createProviderFixture();
        ProviderFixture owner = createProviderFixture();
        ProviderUnavailability block = saveBlock(
                owner.provider(), futureStart(), ProviderUnavailabilityStatus.PENDING);

        performAction(caller.providerUser(), block.getId(), "cancel", true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROVIDER_BLOCK_OWNERSHIP"));

        assertEquals(
                ProviderUnavailabilityStatus.PENDING,
                providerUnavailabilityRepository.findById(block.getId())
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void adminCancelsAnyProvidersPendingBlock() throws Exception {
        ProviderFixture fixture = createProviderFixture();
        ProviderUnavailability block = saveBlock(
                fixture.provider(), futureStart(), ProviderUnavailabilityStatus.PENDING);
        User admin = saveUser(UserRole.ADMIN, "Administrator");

        performAction(admin, block.getId(), "cancel", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(ProviderUnavailabilityStatus.CANCELLED.name()));

        assertEquals(
                ProviderUnavailabilityStatus.CANCELLED,
                providerUnavailabilityRepository.findById(block.getId())
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void unauthenticatedProviderUnavailabilityRequestsAreUnauthorized() throws Exception {
        ProviderFixture fixture = createProviderFixture();
        ProviderUnavailability block = saveBlock(
                fixture.provider(), futureStart(), ProviderUnavailabilityStatus.PENDING);
        Instant startAt = futureStart();

        mockMvc.perform(post("/api/provider-unavailability")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(
                                fixture.provider().getId(),
                                startAt,
                                startAt.plusSeconds(3_600))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(
                                "/api/provider-unavailability/{id}/activate",
                                block.getId())
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(
                                "/api/provider-unavailability/{id}/cancel",
                                block.getId())
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void providerUnavailabilityActionsRequireCsrfTokens() throws Exception {
        ProviderFixture fixture = createProviderFixture();
        ProviderUnavailability activationBlock = saveBlock(
                fixture.provider(), futureStart(), ProviderUnavailabilityStatus.PENDING);
        ProviderUnavailability cancellationBlock = saveBlock(
                fixture.provider(), futureStart().plusSeconds(7_200),
                ProviderUnavailabilityStatus.PENDING);
        User admin = saveUser(UserRole.ADMIN, "Administrator");
        Instant startAt = futureStart().plusSeconds(14_400);

        performCreate(
                        fixture.providerUser(),
                        null,
                        startAt,
                        startAt.plusSeconds(3_600),
                        false)
                .andExpect(status().isForbidden());
        performAction(admin, activationBlock.getId(), "activate", false)
                .andExpect(status().isForbidden());
        performAction(fixture.providerUser(), cancellationBlock.getId(), "cancel", false)
                .andExpect(status().isForbidden());

        assertEquals(2L, providerUnavailabilityRepository.count());
        assertEquals(
                ProviderUnavailabilityStatus.PENDING,
                providerUnavailabilityRepository.findById(activationBlock.getId())
                        .orElseThrow()
                        .getStatus());
        assertEquals(
                ProviderUnavailabilityStatus.PENDING,
                providerUnavailabilityRepository.findById(cancellationBlock.getId())
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void activatingActiveBlockUsesExistingNotPendingMapping() throws Exception {
        ProviderFixture fixture = createProviderFixture();
        ProviderUnavailability block = saveBlock(
                fixture.provider(), futureStart(), ProviderUnavailabilityStatus.ACTIVE);
        User admin = saveUser(UserRole.ADMIN, "Administrator");

        performAction(admin, block.getId(), "activate", true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROVIDER_BLOCK_NOT_PENDING"));

        assertEquals(
                ProviderUnavailabilityStatus.ACTIVE,
                providerUnavailabilityRepository.findById(block.getId())
                        .orElseThrow()
                        .getStatus());
    }

    private ResultActions performCreate(
            User caller,
            Long providerId,
            Instant startAt,
            Instant endAt,
            boolean includeCsrf) throws Exception {
        var request = post("/api/provider-unavailability")
                .with(user(new AuthenticatedUser(caller)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson(providerId, startAt, endAt));
        if (includeCsrf) {
            request.with(csrf());
        }
        return mockMvc.perform(request);
    }

    private ResultActions performAction(
            User caller,
            Long blockId,
            String action,
            boolean includeCsrf) throws Exception {
        var request = post(
                        "/api/provider-unavailability/{id}/{action}",
                        blockId,
                        action)
                .with(user(new AuthenticatedUser(caller)));
        if (includeCsrf) {
            request.with(csrf());
        }
        return mockMvc.perform(request);
    }

    private String requestJson(Long providerId, Instant startAt, Instant endAt)
            throws Exception {
        return objectMapper.writeValueAsString(new RequestProviderBlockRequest(
                providerId, startAt, endAt, "Conference"));
    }

    private ProviderFixture createProviderFixture() {
        long number = SEQUENCE.incrementAndGet();

        Specialty specialty = new Specialty();
        specialty.setName("Provider Unavailability Specialty " + number);
        Specialty savedSpecialty = specialtyRepository.saveAndFlush(specialty);

        User providerUser = saveUser(UserRole.PROVIDER, "Provider");
        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(savedSpecialty.getId());
        provider.setLicenseNumber("UNAVAILABILITY-" + number);
        Provider savedProvider = providerRepository.saveAndFlush(provider);

        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName("Provider Unavailability Type " + number);
        appointmentType.setDurationMinutes(60);
        appointmentType.setSpecialtyId(savedSpecialty.getId());
        appointmentType.setActive(true);
        AppointmentType savedAppointmentType =
                appointmentTypeRepository.saveAndFlush(appointmentType);

        return new ProviderFixture(
                providerUser, savedProvider, savedAppointmentType);
    }

    private Appointment saveScheduledAppointment(
            ProviderFixture fixture, Instant startAt, Instant endAt) {
        User patient = saveUser(UserRole.PATIENT, "Patient");
        Appointment appointment = new Appointment();
        appointment.setPatientId(patient.getId());
        appointment.setProviderId(fixture.provider().getId());
        appointment.setAppointmentTypeId(fixture.appointmentType().getId());
        appointment.setStartAt(startAt);
        appointment.setEndAt(endAt);
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        return appointmentRepository.saveAndFlush(appointment);
    }

    private ProviderUnavailability saveBlock(
            Provider provider,
            Instant startAt,
            ProviderUnavailabilityStatus status) {
        ProviderUnavailability block = new ProviderUnavailability();
        block.setProviderId(provider.getId());
        block.setStartAt(startAt);
        block.setEndAt(startAt.plusSeconds(3_600));
        block.setStatus(status);
        block.setReason("Conference");
        if (status == ProviderUnavailabilityStatus.ACTIVE) {
            block.setActivatedAt(Instant.now());
        }
        return providerUnavailabilityRepository.saveAndFlush(block);
    }

    private User saveUser(UserRole role, String displayName) {
        long number = SEQUENCE.incrementAndGet();
        User user = new User();
        user.setEmail("provider-unavailability-" + role.name().toLowerCase()
                + "-" + number + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName(displayName);
        return userRepository.saveAndFlush(user);
    }

    private Instant futureStart() {
        return Instant.parse("2070-01-01T14:00:00Z")
                .plusSeconds(SEQUENCE.incrementAndGet() * 86_400);
    }

    private record ProviderFixture(
            User providerUser,
            Provider provider,
            AppointmentType appointmentType) {
    }
}

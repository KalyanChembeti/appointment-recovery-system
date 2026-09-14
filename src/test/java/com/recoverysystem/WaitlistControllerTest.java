package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.Specialty;
import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.TimeOfDayPreference;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.web.dto.JoinWaitlistRequest;
import com.recoverysystem.web.dto.ModifyWaitlistRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
class WaitlistControllerTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ZoneId CLINIC_TIME_ZONE = ZoneId.of("America/New_York");

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
    private AppointmentRepository appointmentRepository;

    @Autowired
    private WaitlistEntryRepository waitlistEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearWorkflowData() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_log, slot_offer, recovery_job, "
                + "waitlist_entry, appointment, provider_schedule, provider_unavailability, "
                + "provider, appointment_type, specialty, users RESTART IDENTITY CASCADE");
    }

    @Test
    void patientCanJoinWaitlistForOwnAppointment() throws Exception {
        WaitlistFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.appointmentStartAt());

        performJoin(fixture.firstPatient(), joinRequest(fixture, appointment), true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.patientId").value(fixture.firstPatient().getId()))
                .andExpect(jsonPath("$.currentAppointmentId").value(appointment.getId()))
                .andExpect(jsonPath("$.appointmentTypeId")
                        .value(fixture.appointmentType().getId()))
                .andExpect(jsonPath("$.preferredProviderId").doesNotExist())
                .andExpect(jsonPath("$.earliestAppointmentDate")
                        .value(fixture.earliestDate().toString()))
                .andExpect(jsonPath("$.latestAppointmentDate")
                        .value(fixture.latestDate().toString()))
                .andExpect(jsonPath("$.preferredTimeOfDay")
                        .value(TimeOfDayPreference.ANY.name()))
                .andExpect(jsonPath("$.status").value(WaitlistEntryStatus.ACTIVE.name()));

        WaitlistEntry persisted = waitlistEntryRepository.findAll().getFirst();
        assertEquals(fixture.firstPatient().getId(), persisted.getPatientId());
        assertEquals(appointment.getId(), persisted.getCurrentAppointmentId());
    }

    @Test
    void patientCanViewOwnWaitlistEntry() throws Exception {
        WaitlistFixture fixture = createFixture();
        WaitlistEntry entry = saveWaitlistEntry(
                fixture,
                fixture.firstPatient(),
                saveScheduledAppointment(
                        fixture, fixture.firstPatient(), fixture.appointmentStartAt()));

        performGet(fixture.firstPatient(), entry.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(entry.getId()))
                .andExpect(jsonPath("$.patientId").value(fixture.firstPatient().getId()))
                .andExpect(jsonPath("$.currentAppointmentId")
                        .value(entry.getCurrentAppointmentId()))
                .andExpect(jsonPath("$.status").value(WaitlistEntryStatus.ACTIVE.name()));
    }

    @Test
    void patientCanModifyOwnWaitlistEntry() throws Exception {
        WaitlistFixture fixture = createFixture();
        WaitlistEntry entry = saveWaitlistEntry(
                fixture,
                fixture.firstPatient(),
                saveScheduledAppointment(
                        fixture, fixture.firstPatient(), fixture.appointmentStartAt()));
        LocalDate newEarliestDate = fixture.earliestDate().plusDays(1);
        LocalDate newLatestDate = fixture.latestDate().plusDays(2);
        ModifyWaitlistRequest request = new ModifyWaitlistRequest(
                newEarliestDate,
                newLatestDate,
                fixture.provider().getId(),
                TimeOfDayPreference.MORNING);

        performModify(fixture.firstPatient(), entry.getId(), request, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(entry.getId()))
                .andExpect(jsonPath("$.patientId").value(fixture.firstPatient().getId()))
                .andExpect(jsonPath("$.preferredProviderId")
                        .value(fixture.provider().getId()))
                .andExpect(jsonPath("$.earliestAppointmentDate")
                        .value(newEarliestDate.toString()))
                .andExpect(jsonPath("$.latestAppointmentDate")
                        .value(newLatestDate.toString()))
                .andExpect(jsonPath("$.preferredTimeOfDay")
                        .value(TimeOfDayPreference.MORNING.name()));

        WaitlistEntry persisted = waitlistEntryRepository.findById(entry.getId()).orElseThrow();
        assertEquals(newEarliestDate, persisted.getEarliestAppointmentDate());
        assertEquals(newLatestDate, persisted.getLatestAppointmentDate());
        assertEquals(fixture.provider().getId(), persisted.getPreferredProviderId());
        assertEquals(TimeOfDayPreference.MORNING, persisted.getPreferredTimeOfDay());
    }

    @Test
    void patientCanRemoveOwnWaitlistEntry() throws Exception {
        WaitlistFixture fixture = createFixture();
        WaitlistEntry entry = saveWaitlistEntry(
                fixture,
                fixture.firstPatient(),
                saveScheduledAppointment(
                        fixture, fixture.firstPatient(), fixture.appointmentStartAt()));

        performRemove(fixture.firstPatient(), entry.getId(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(entry.getId()))
                .andExpect(jsonPath("$.patientId").value(fixture.firstPatient().getId()))
                .andExpect(jsonPath("$.status").value(WaitlistEntryStatus.REMOVED.name()));

        WaitlistEntry persisted = waitlistEntryRepository.findById(entry.getId()).orElseThrow();
        assertEquals(WaitlistEntryStatus.REMOVED, persisted.getStatus());
    }

    @Test
    void patientCannotViewModifyOrRemoveAnotherPatientsWaitlistEntry() throws Exception {
        WaitlistFixture fixture = createFixture();
        WaitlistEntry entry = saveWaitlistEntry(
                fixture,
                fixture.secondPatient(),
                saveScheduledAppointment(
                        fixture, fixture.secondPatient(), fixture.appointmentStartAt()));
        ModifyWaitlistRequest request = modifyRequest(fixture);

        performGet(fixture.firstPatient(), entry.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WAITLIST_ENTRY_OWNERSHIP"));
        performModify(fixture.firstPatient(), entry.getId(), request, true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WAITLIST_ENTRY_OWNERSHIP"));
        performRemove(fixture.firstPatient(), entry.getId(), true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WAITLIST_ENTRY_OWNERSHIP"));

        WaitlistEntry persisted = waitlistEntryRepository.findById(entry.getId()).orElseThrow();
        assertEquals(WaitlistEntryStatus.ACTIVE, persisted.getStatus());
        assertNull(persisted.getPreferredProviderId());
    }

    @Test
    void staffCannotJoinViewOrModifyWaitlistEntries() throws Exception {
        WaitlistFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.appointmentStartAt());
        WaitlistEntry entry = saveWaitlistEntry(fixture, fixture.firstPatient(), appointment);
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");
        User admin = saveUser(UserRole.ADMIN, "Administrator");

        for (User staff : new User[] {receptionist, admin}) {
            performJoin(staff, joinRequest(fixture, appointment), true)
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(""));
            performGet(staff, entry.getId())
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(""));
            performModify(staff, entry.getId(), modifyRequest(fixture), true)
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(""));
        }
    }

    @Test
    void receptionistAndAdminCanRemoveAnyPatientsWaitlistEntry() throws Exception {
        WaitlistFixture fixture = createFixture();
        WaitlistEntry receptionistTarget = saveWaitlistEntry(
                fixture,
                fixture.firstPatient(),
                saveScheduledAppointment(
                        fixture, fixture.firstPatient(), fixture.appointmentStartAt()));
        WaitlistEntry adminTarget = saveWaitlistEntry(
                fixture,
                fixture.secondPatient(),
                saveScheduledAppointment(
                        fixture,
                        fixture.secondPatient(),
                        fixture.appointmentStartAt().plusSeconds(7_200)));
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");
        User admin = saveUser(UserRole.ADMIN, "Administrator");

        performRemove(receptionist, receptionistTarget.getId(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(WaitlistEntryStatus.REMOVED.name()));
        performRemove(admin, adminTarget.getId(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(WaitlistEntryStatus.REMOVED.name()));

        assertEquals(
                WaitlistEntryStatus.REMOVED,
                waitlistEntryRepository.findById(receptionistTarget.getId())
                        .orElseThrow()
                        .getStatus());
        assertEquals(
                WaitlistEntryStatus.REMOVED,
                waitlistEntryRepository.findById(adminTarget.getId())
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void providerCannotUseAnyWaitlistEndpoint() throws Exception {
        WaitlistFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.appointmentStartAt());
        WaitlistEntry entry = saveWaitlistEntry(fixture, fixture.firstPatient(), appointment);
        User provider = fixture.providerUser();

        performJoin(provider, joinRequest(fixture, appointment), true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROVIDER_ACTION_NOT_PERMITTED"));
        performGet(provider, entry.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROVIDER_ACTION_NOT_PERMITTED"));
        performModify(provider, entry.getId(), modifyRequest(fixture), true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROVIDER_ACTION_NOT_PERMITTED"));
        performRemove(provider, entry.getId(), true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROVIDER_ACTION_NOT_PERMITTED"));
    }

    @Test
    void unauthenticatedWaitlistRequestsAreUnauthorized() throws Exception {
        WaitlistFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.appointmentStartAt());
        WaitlistEntry entry = saveWaitlistEntry(fixture, fixture.firstPatient(), appointment);

        mockMvc.perform(post("/api/waitlist")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                joinRequest(fixture, appointment))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/waitlist/{id}", entry.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/waitlist/{id}", entry.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(modifyRequest(fixture))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/waitlist/{id}", entry.getId()).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsafeWaitlistRequestsRequireCsrfToken() throws Exception {
        WaitlistFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.appointmentStartAt());
        WaitlistEntry entry = saveWaitlistEntry(fixture, fixture.firstPatient(), appointment);

        performJoin(fixture.firstPatient(), joinRequest(fixture, appointment), false)
                .andExpect(status().isForbidden());
        performModify(fixture.firstPatient(), entry.getId(), modifyRequest(fixture), false)
                .andExpect(status().isForbidden());
        performRemove(fixture.firstPatient(), entry.getId(), false)
                .andExpect(status().isForbidden());
    }

    @Test
    void joiningWithAnotherPatientsAnchorUsesExistingOwnershipMapping() throws Exception {
        WaitlistFixture fixture = createFixture();
        Appointment anotherPatientsAppointment = saveScheduledAppointment(
                fixture, fixture.secondPatient(), fixture.appointmentStartAt());

        performJoin(
                        fixture.firstPatient(),
                        joinRequest(fixture, anotherPatientsAppointment),
                        true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WAITLIST_ANCHOR_OWNERSHIP"));

        assertEquals(0L, waitlistEntryRepository.count());
    }

    @Test
    void nonexistentWaitlistEntryIsNotFoundForGetModifyAndRemove() throws Exception {
        WaitlistFixture fixture = createFixture();
        Long nonexistentId = Long.MAX_VALUE;

        performGet(fixture.firstPatient(), nonexistentId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITLIST_ENTRY_NOT_FOUND"));
        performModify(fixture.firstPatient(), nonexistentId, modifyRequest(fixture), true)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITLIST_ENTRY_NOT_FOUND"));
        performRemove(fixture.firstPatient(), nonexistentId, true)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITLIST_ENTRY_NOT_FOUND"));
    }

    private ResultActions performJoin(
            User caller, JoinWaitlistRequest requestBody, boolean includeCsrf) throws Exception {
        var request = post("/api/waitlist")
                .with(user(new AuthenticatedUser(caller)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody));
        if (includeCsrf) {
            request.with(csrf());
        }
        return mockMvc.perform(request);
    }

    private ResultActions performGet(User caller, Long waitlistEntryId) throws Exception {
        return mockMvc.perform(get("/api/waitlist/{id}", waitlistEntryId)
                .with(user(new AuthenticatedUser(caller))));
    }

    private ResultActions performModify(
            User caller,
            Long waitlistEntryId,
            ModifyWaitlistRequest requestBody,
            boolean includeCsrf) throws Exception {
        var request = put("/api/waitlist/{id}", waitlistEntryId)
                .with(user(new AuthenticatedUser(caller)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody));
        if (includeCsrf) {
            request.with(csrf());
        }
        return mockMvc.perform(request);
    }

    private ResultActions performRemove(
            User caller, Long waitlistEntryId, boolean includeCsrf) throws Exception {
        var request = delete("/api/waitlist/{id}", waitlistEntryId)
                .with(user(new AuthenticatedUser(caller)));
        if (includeCsrf) {
            request.with(csrf());
        }
        return mockMvc.perform(request);
    }

    private JoinWaitlistRequest joinRequest(
            WaitlistFixture fixture, Appointment appointment) {
        return new JoinWaitlistRequest(
                appointment.getId(),
                fixture.appointmentType().getId(),
                null,
                fixture.earliestDate(),
                fixture.latestDate(),
                null);
    }

    private ModifyWaitlistRequest modifyRequest(WaitlistFixture fixture) {
        return new ModifyWaitlistRequest(
                fixture.earliestDate().plusDays(1),
                fixture.latestDate().plusDays(1),
                fixture.provider().getId(),
                TimeOfDayPreference.AFTERNOON);
    }

    private Appointment saveScheduledAppointment(
            WaitlistFixture fixture, User patient, Instant startAt) {
        Appointment appointment = new Appointment();
        appointment.setPatientId(patient.getId());
        appointment.setProviderId(fixture.provider().getId());
        appointment.setAppointmentTypeId(fixture.appointmentType().getId());
        appointment.setStartAt(startAt);
        appointment.setEndAt(startAt.plusSeconds(3_600));
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        return appointmentRepository.saveAndFlush(appointment);
    }

    private WaitlistEntry saveWaitlistEntry(
            WaitlistFixture fixture, User patient, Appointment appointment) {
        WaitlistEntry entry = new WaitlistEntry();
        entry.setPatientId(patient.getId());
        entry.setCurrentAppointmentId(appointment.getId());
        entry.setAppointmentTypeId(fixture.appointmentType().getId());
        entry.setPreferredProviderId(null);
        entry.setEarliestAppointmentDate(fixture.earliestDate());
        entry.setLatestAppointmentDate(fixture.latestDate());
        entry.setPreferredTimeOfDay(TimeOfDayPreference.ANY);
        entry.setStatus(WaitlistEntryStatus.ACTIVE);
        return waitlistEntryRepository.saveAndFlush(entry);
    }

    private WaitlistFixture createFixture() {
        long number = SEQUENCE.incrementAndGet();
        LocalDate appointmentDate = LocalDate.of(2045, 1, 15).plusDays(number);
        Instant appointmentStartAt = appointmentDate.atTime(12, 0)
                .atZone(CLINIC_TIME_ZONE)
                .toInstant();

        Specialty specialty = new Specialty();
        specialty.setName("Waitlist API Specialty " + number);
        Specialty savedSpecialty = specialtyRepository.saveAndFlush(specialty);

        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName("Waitlist API Type " + number);
        appointmentType.setDurationMinutes(60);
        appointmentType.setSpecialtyId(savedSpecialty.getId());
        appointmentType.setActive(true);
        AppointmentType savedAppointmentType =
                appointmentTypeRepository.saveAndFlush(appointmentType);

        User providerUser = saveUser(UserRole.PROVIDER, "Provider");
        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(savedSpecialty.getId());
        provider.setLicenseNumber("WAITLIST-API-" + number);
        Provider savedProvider = providerRepository.saveAndFlush(provider);

        User firstPatient = saveUser(UserRole.PATIENT, "First Patient");
        User secondPatient = saveUser(UserRole.PATIENT, "Second Patient");
        return new WaitlistFixture(
                firstPatient,
                secondPatient,
                providerUser,
                savedProvider,
                savedAppointmentType,
                appointmentStartAt,
                appointmentDate.minusDays(14),
                appointmentDate.minusDays(1));
    }

    private User saveUser(UserRole role, String displayName) {
        long number = SEQUENCE.incrementAndGet();
        User user = new User();
        user.setEmail("waitlist-api-" + role.name().toLowerCase()
                + "-" + number + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName(displayName);
        return userRepository.saveAndFlush(user);
    }

    private record WaitlistFixture(
            User firstPatient,
            User secondPatient,
            User providerUser,
            Provider provider,
            AppointmentType appointmentType,
            Instant appointmentStartAt,
            LocalDate earliestDate,
            LocalDate latestDate) {
    }
}

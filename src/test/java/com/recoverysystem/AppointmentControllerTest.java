package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.ProviderSchedule;
import com.recoverysystem.domain.entity.Specialty;
import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.CancellationReason;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.exception.ProviderActionNotPermittedException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderScheduleRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.service.DirectBookingService;
import com.recoverysystem.web.dto.AppointmentResponse;
import com.recoverysystem.web.dto.BookAppointmentRequest;
import com.recoverysystem.web.dto.RescheduleAppointmentRequest;
import com.recoverysystem.web.security.EffectivePatientIdResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
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
class AppointmentControllerTest {

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
    private EffectivePatientIdResolver effectivePatientIdResolver;

    @Autowired
    private DirectBookingService directBookingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SpecialtyRepository specialtyRepository;

    @Autowired
    private AppointmentTypeRepository appointmentTypeRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private ProviderScheduleRepository providerScheduleRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

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
    void patientCanBookForSelfWithoutPatientId() throws Exception {
        BookingFixture fixture = createFixture();

        performBooking(fixture.firstPatient(), null, fixture, true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.patientId").value(fixture.firstPatient().getId()))
                .andExpect(jsonPath("$.providerId").value(fixture.provider().getId()))
                .andExpect(jsonPath("$.appointmentTypeId")
                        .value(fixture.appointmentType().getId()))
                .andExpect(jsonPath("$.startAt").value(fixture.startAt().toString()))
                .andExpect(jsonPath("$.endAt")
                        .value(fixture.startAt().plusSeconds(3_600).toString()))
                .andExpect(jsonPath("$.status").value(AppointmentStatus.SCHEDULED.name()));

        Appointment appointment = appointmentRepository.findAll().getFirst();
        List<AuditLog> audits = auditLogRepository.findAll();
        assertEquals(1, audits.size());
        assertEquals(appointment.getId(), audits.getFirst().getEntityId());
        assertEquals(ActorType.USER, audits.getFirst().getActorType());
        assertEquals(fixture.firstPatient().getId(), audits.getFirst().getActorUserId());
    }

    @Test
    void patientCanBookWithExplicitOwnPatientId() throws Exception {
        BookingFixture fixture = createFixture();

        performBooking(
                        fixture.firstPatient(),
                        fixture.firstPatient().getId(),
                        fixture,
                        true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.patientId").value(fixture.firstPatient().getId()));
    }

    @Test
    void patientCannotBookForDifferentPatient() throws Exception {
        BookingFixture fixture = createFixture();

        performBooking(
                        fixture.firstPatient(),
                        fixture.secondPatient().getId(),
                        fixture,
                        true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PATIENT_IDENTITY_MISMATCH"));

        assertEquals(0L, appointmentRepository.count());
    }

    @Test
    void receptionistCanBookForSpecifiedPatient() throws Exception {
        BookingFixture fixture = createFixture();
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");

        performBooking(
                        receptionist,
                        fixture.firstPatient().getId(),
                        fixture,
                        true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.patientId").value(fixture.firstPatient().getId()));

        AuditLog audit = auditLogRepository.findAll().getFirst();
        assertEquals(receptionist.getId(), audit.getActorUserId());
    }

    @Test
    void receptionistMustSpecifyPatientId() throws Exception {
        BookingFixture fixture = createFixture();
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");

        performBooking(receptionist, null, fixture, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PATIENT_ID"));
    }

    @Test
    void providerCannotPerformPatientBookingAction() throws Exception {
        BookingFixture fixture = createFixture();
        AuthenticatedUser providerCaller = new AuthenticatedUser(fixture.providerUser());

        assertThrows(
                ProviderActionNotPermittedException.class,
                () -> effectivePatientIdResolver.resolve(
                        fixture.firstPatient().getId(), providerCaller));

        mockMvc.perform(post("/api/appointments")
                        .with(user(providerCaller))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROVIDER_ACTION_NOT_PERMITTED"));
    }

    @Test
    void unauthenticatedBookingIsUnauthorized() throws Exception {
        BookingFixture fixture = createFixture();

        mockMvc.perform(post("/api/appointments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(null, fixture)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bookingWithoutCsrfTokenIsForbidden() throws Exception {
        BookingFixture fixture = createFixture();

        performBooking(fixture.firstPatient(), null, fixture, false)
                .andExpect(status().isForbidden());
    }

    @Test
    void providerDoubleBookingUsesExistingGlobalExceptionMapping() throws Exception {
        BookingFixture fixture = createFixture();
        directBookingService.bookAppointment(
                fixture.firstPatient().getId(),
                fixture.provider().getId(),
                fixture.appointmentType().getId(),
                fixture.startAt(),
                fixture.firstPatient().getId());

        performBooking(fixture.secondPatient(), null, fixture, true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROVIDER_DOUBLE_BOOKED"));

        assertEquals(1L, appointmentRepository.count());
    }

    @Test
    void patientCanCancelOwnAppointmentWithoutReasonText() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());

        performCancel(fixture.firstPatient(), appointment.getId(), "{}", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appointment.getId()))
                .andExpect(jsonPath("$.status").value(AppointmentStatus.CANCELLED.name()));

        Appointment persisted = appointmentRepository.findById(appointment.getId()).orElseThrow();
        assertEquals(AppointmentStatus.CANCELLED, persisted.getStatus());
        assertEquals(CancellationReason.PATIENT_CANCELLED, persisted.getCancellationReason());
    }

    @Test
    void cancellationReasonTextReachesPersistedAuditThroughHttpEndpoint() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());
        String reasonText = "Patient reported a schedule conflict";

        performCancel(
                        fixture.firstPatient(),
                        appointment.getId(),
                        "{\"reasonText\":\"" + reasonText + "\"}",
                        true)
                .andExpect(status().isOk());

        AuditLog cancellationAudit = auditLogRepository.findAll().stream()
                .filter(log -> "Appointment".equals(log.getEntityType()))
                .filter(log -> appointment.getId().equals(log.getEntityId()))
                .filter(log -> "CANCEL".equals(log.getAction()))
                .findFirst()
                .orElseThrow();
        assertEquals(reasonText, cancellationAudit.getReason());
    }

    @Test
    void patientCannotCancelAnotherPatientsAppointment() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.secondPatient(), fixture.startAt());

        performCancel(fixture.firstPatient(), appointment.getId(), "{}", true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_OWNERSHIP"));

        Appointment persisted = appointmentRepository.findById(appointment.getId()).orElseThrow();
        assertEquals(AppointmentStatus.SCHEDULED, persisted.getStatus());
        assertNull(persisted.getCancellationReason());
    }

    @Test
    void receptionistCanCancelAnyPatientsAppointment() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");

        performCancel(receptionist, appointment.getId(), "{}", true)
                .andExpect(status().isOk());

        Appointment persisted = appointmentRepository.findById(appointment.getId()).orElseThrow();
        assertEquals(AppointmentStatus.CANCELLED, persisted.getStatus());
        assertEquals(CancellationReason.STAFF_CANCELLED, persisted.getCancellationReason());
    }

    @Test
    void patientCannotSmuggleCancellationReasonThroughRequestBody() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());

        performCancel(
                        fixture.firstPatient(),
                        appointment.getId(),
                        "{\"cancellationReason\":\"RESCHEDULED\"}",
                        true)
                .andExpect(status().isOk());

        Appointment persisted = appointmentRepository.findById(appointment.getId()).orElseThrow();
        assertEquals(CancellationReason.PATIENT_CANCELLED, persisted.getCancellationReason());
    }

    @Test
    void patientCanRescheduleOwnAppointment() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment oldAppointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());
        Instant newStartAt = fixture.startAt().plusSeconds(7_200);

        MvcResult result = performReschedule(
                        fixture.firstPatient(), oldAppointment.getId(), fixture, newStartAt, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientId").value(fixture.firstPatient().getId()))
                .andExpect(jsonPath("$.providerId").value(fixture.provider().getId()))
                .andExpect(jsonPath("$.appointmentTypeId")
                        .value(fixture.appointmentType().getId()))
                .andExpect(jsonPath("$.startAt").value(newStartAt.toString()))
                .andReturn();

        Long newAppointmentId = responseAppointmentId(result);
        Appointment persisted = appointmentRepository.findById(newAppointmentId).orElseThrow();
        assertEquals(fixture.firstPatient().getId(), persisted.getPatientId());
        assertEquals(fixture.provider().getId(), persisted.getProviderId());
        assertEquals(fixture.appointmentType().getId(), persisted.getAppointmentTypeId());
        assertEquals(newStartAt, persisted.getStartAt());
        assertEquals(newStartAt.plusSeconds(3_600), persisted.getEndAt());
        assertEquals(AppointmentStatus.SCHEDULED, persisted.getStatus());
    }

    @Test
    void rescheduleAuditUsesAuthenticatedCallerAsActor() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment oldAppointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());
        Instant newStartAt = fixture.startAt().plusSeconds(7_200);

        performReschedule(
                        fixture.firstPatient(), oldAppointment.getId(), fixture, newStartAt, true)
                .andExpect(status().isOk());

        List<AuditLog> audits = auditLogRepository.findAll();
        assertTrue(audits.size() > 1);
        assertTrue(audits.stream().anyMatch(audit -> "CREATE".equals(audit.getAction())));
        assertTrue(audits.stream().anyMatch(audit -> "CANCEL".equals(audit.getAction())));
        audits.forEach(audit -> {
            assertEquals(ActorType.USER, audit.getActorType());
            assertEquals(fixture.firstPatient().getId(), audit.getActorUserId());
        });
    }

    @Test
    void patientCannotRescheduleAnotherPatientsAppointment() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment oldAppointment = saveScheduledAppointment(
                fixture, fixture.secondPatient(), fixture.startAt());

        performReschedule(
                        fixture.firstPatient(),
                        oldAppointment.getId(),
                        fixture,
                        fixture.startAt().plusSeconds(7_200),
                        true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_OWNERSHIP"));

        assertEquals(1L, appointmentRepository.count());
        Appointment persisted = appointmentRepository.findById(oldAppointment.getId())
                .orElseThrow();
        assertEquals(AppointmentStatus.SCHEDULED, persisted.getStatus());
        assertNull(persisted.getReplacedByAppointmentId());
    }

    @Test
    void receptionistCanRescheduleAnyPatientsAppointment() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment oldAppointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");
        Instant newStartAt = fixture.startAt().plusSeconds(7_200);

        MvcResult result = performReschedule(
                        receptionist, oldAppointment.getId(), fixture, newStartAt, true)
                .andExpect(status().isOk())
                .andReturn();

        Appointment persisted = appointmentRepository.findById(responseAppointmentId(result))
                .orElseThrow();
        assertEquals(fixture.firstPatient().getId(), persisted.getPatientId());
        assertEquals(fixture.provider().getId(), persisted.getProviderId());
        assertEquals(newStartAt, persisted.getStartAt());
    }

    @Test
    void providerCannotCancelOrRescheduleAppointments() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());
        User provider = fixture.providerUser();

        performCancel(provider, appointment.getId(), "{}", true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROVIDER_ACTION_NOT_PERMITTED"));
        performReschedule(
                        provider,
                        appointment.getId(),
                        fixture,
                        fixture.startAt().plusSeconds(7_200),
                        true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROVIDER_ACTION_NOT_PERMITTED"));
    }

    @Test
    void unauthenticatedCancellationAndReschedulingAreUnauthorized() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());

        mockMvc.perform(post("/api/appointments/{id}/cancel", appointment.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/appointments/{id}/reschedule", appointment.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleRequestJson(
                                fixture, fixture.startAt().plusSeconds(7_200))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cancellationAndReschedulingWithoutCsrfAreForbidden() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());

        performCancel(fixture.firstPatient(), appointment.getId(), "{}", false)
                .andExpect(status().isForbidden());
        performReschedule(
                        fixture.firstPatient(),
                        appointment.getId(),
                        fixture,
                        fixture.startAt().plusSeconds(7_200),
                        false)
                .andExpect(status().isForbidden());
    }

    @Test
    void receptionistCanCompletePastScheduledAppointment() throws Exception {
        BookingFixture fixture = createFixture();
        Instant pastStartAt = Instant.now().minusSeconds(7_200);
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), pastStartAt);
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");

        performAppointmentAction(receptionist, appointment.getId(), "complete", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appointment.getId()))
                .andExpect(jsonPath("$.status").value(AppointmentStatus.COMPLETED.name()));

        Appointment persisted = appointmentRepository.findById(appointment.getId()).orElseThrow();
        assertEquals(AppointmentStatus.COMPLETED, persisted.getStatus());
    }

    @Test
    void completionAuditUsesAuthenticatedReceptionistAsActor() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), Instant.now().minusSeconds(7_200));
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");

        performAppointmentAction(receptionist, appointment.getId(), "complete", true)
                .andExpect(status().isOk());

        AuditLog audit = auditLogRepository.findAll().getFirst();
        assertEquals("Appointment", audit.getEntityType());
        assertEquals(appointment.getId(), audit.getEntityId());
        assertEquals("COMPLETED", audit.getAction());
        assertEquals(ActorType.USER, audit.getActorType());
        assertEquals(receptionist.getId(), audit.getActorUserId());
    }

    @Test
    void completionBeforeStartUsesExistingGlobalExceptionMapping() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), Instant.now().plusSeconds(7_200));
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");

        performAppointmentAction(receptionist, appointment.getId(), "complete", true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_YET_STARTED"));

        Appointment persisted = appointmentRepository.findById(appointment.getId()).orElseThrow();
        assertEquals(AppointmentStatus.SCHEDULED, persisted.getStatus());
    }

    @Test
    void receptionistCanMarkScheduledAppointmentNoShow() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");

        performAppointmentAction(receptionist, appointment.getId(), "mark-no-show", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appointment.getId()))
                .andExpect(jsonPath("$.status").value(AppointmentStatus.NO_SHOW.name()));

        Appointment persisted = appointmentRepository.findById(appointment.getId()).orElseThrow();
        assertEquals(AppointmentStatus.NO_SHOW, persisted.getStatus());
    }

    @Test
    void noShowAuditUsesAuthenticatedReceptionistAsActor() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");

        performAppointmentAction(receptionist, appointment.getId(), "mark-no-show", true)
                .andExpect(status().isOk());

        AuditLog audit = auditLogRepository.findAll().getFirst();
        assertEquals("Appointment", audit.getEntityType());
        assertEquals(appointment.getId(), audit.getEntityId());
        assertEquals("NO_SHOW", audit.getAction());
        assertEquals(ActorType.USER, audit.getActorType());
        assertEquals(receptionist.getId(), audit.getActorUserId());
    }

    @Test
    void oldNoShowPathNoLongerResolves() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");

        mockMvc.perform(post("/api/appointments/{id}/no-show", appointment.getId())
                        .with(user(new AuthenticatedUser(receptionist)))
                        .with(csrf()))
                .andExpect(status().isNotFound());

        Appointment persisted = appointmentRepository.findById(appointment.getId()).orElseThrow();
        assertEquals(AppointmentStatus.SCHEDULED, persisted.getStatus());
    }

    @Test
    void patientCannotCompleteOrMarkAppointmentsNoShow() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), Instant.now().minusSeconds(7_200));

        performAppointmentAction(
                        fixture.firstPatient(), appointment.getId(), "complete", true)
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));
        performAppointmentAction(
                        fixture.firstPatient(), appointment.getId(), "mark-no-show", true)
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));

        Appointment persisted = appointmentRepository.findById(appointment.getId()).orElseThrow();
        assertEquals(AppointmentStatus.SCHEDULED, persisted.getStatus());
        assertEquals(0L, auditLogRepository.count());
    }

    @Test
    void providerCannotCompleteOrMarkAppointmentsNoShow() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), Instant.now().minusSeconds(7_200));

        performAppointmentAction(
                        fixture.providerUser(), appointment.getId(), "complete", true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROVIDER_ACTION_NOT_PERMITTED"));
        performAppointmentAction(
                        fixture.providerUser(), appointment.getId(), "mark-no-show", true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROVIDER_ACTION_NOT_PERMITTED"));
    }

    @Test
    void unauthenticatedCompletionAndNoShowAreUnauthorized() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), Instant.now().minusSeconds(7_200));

        mockMvc.perform(post("/api/appointments/{id}/complete", appointment.getId())
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/appointments/{id}/mark-no-show", appointment.getId())
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void completionAndNoShowWithoutCsrfAreForbidden() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), Instant.now().minusSeconds(7_200));
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");

        performAppointmentAction(receptionist, appointment.getId(), "complete", false)
                .andExpect(status().isForbidden());
        performAppointmentAction(receptionist, appointment.getId(), "mark-no-show", false)
                .andExpect(status().isForbidden());
    }

    @Test
    void completingCancelledAppointmentUsesExistingNotScheduledMapping() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), Instant.now().minusSeconds(7_200));
        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setCancellationReason(CancellationReason.PATIENT_CANCELLED);
        appointmentRepository.saveAndFlush(appointment);
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");

        performAppointmentAction(receptionist, appointment.getId(), "complete", true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_SCHEDULED"));
    }

    @Test
    void patientCanFetchOwnAppointment() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());

        performAppointmentGet(fixture.firstPatient(), appointment.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appointment.getId()))
                .andExpect(jsonPath("$.patientId").value(fixture.firstPatient().getId()))
                .andExpect(jsonPath("$.providerId").value(fixture.provider().getId()))
                .andExpect(jsonPath("$.appointmentTypeId")
                        .value(fixture.appointmentType().getId()))
                .andExpect(jsonPath("$.startAt").value(fixture.startAt().toString()))
                .andExpect(jsonPath("$.endAt")
                        .value(fixture.startAt().plusSeconds(3_600).toString()))
                .andExpect(jsonPath("$.status").value(AppointmentStatus.SCHEDULED.name()));
    }

    @Test
    void patientCannotFetchAnotherPatientsAppointment() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.secondPatient(), fixture.startAt());

        performAppointmentGet(fixture.firstPatient(), appointment.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_OWNERSHIP"));
    }

    @Test
    void providerCanFetchAssignedAppointmentUsingProviderRecordId() throws Exception {
        saveUser(UserRole.PATIENT, "ID space offset");
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());
        assertNotEquals(fixture.providerUser().getId(), fixture.provider().getId());

        performAppointmentGet(fixture.providerUser(), appointment.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appointment.getId()))
                .andExpect(jsonPath("$.providerId").value(fixture.provider().getId()));
    }

    @Test
    void providerCannotFetchAnotherProvidersAppointment() throws Exception {
        BookingFixture callerFixture = createFixture();
        BookingFixture appointmentFixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                appointmentFixture,
                appointmentFixture.firstPatient(),
                appointmentFixture.startAt());

        performAppointmentGet(callerFixture.providerUser(), appointment.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_OWNERSHIP"));
    }

    @Test
    void receptionistAndAdminCanFetchAnyAppointment() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");
        User admin = saveUser(UserRole.ADMIN, "Administrator");

        performAppointmentGet(receptionist, appointment.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appointment.getId()));
        performAppointmentGet(admin, appointment.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appointment.getId()));
    }

    @Test
    void nonexistentAppointmentFetchIsNotFound() throws Exception {
        BookingFixture fixture = createFixture();

        performAppointmentGet(fixture.firstPatient(), Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));
    }

    @Test
    void unauthenticatedAppointmentFetchIsUnauthorized() throws Exception {
        BookingFixture fixture = createFixture();
        Appointment appointment = saveScheduledAppointment(
                fixture, fixture.firstPatient(), fixture.startAt());

        mockMvc.perform(get("/api/appointments/{id}", appointment.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patientListContainsOnlyTheirOwnAppointments() throws Exception {
        BookingFixture firstFixture = createFixture();
        BookingFixture secondFixture = createFixture();
        User caller = firstFixture.firstPatient();
        Appointment firstOwnAppointment = saveScheduledAppointment(
                firstFixture, caller, firstFixture.startAt());
        Appointment secondOwnAppointment = saveScheduledAppointment(
                secondFixture, caller, secondFixture.startAt());
        Appointment otherPatientsAppointment = saveScheduledAppointment(
                secondFixture,
                secondFixture.secondPatient(),
                secondFixture.startAt().plusSeconds(7_200));

        MvcResult result = performAppointmentList(caller)
                .andExpect(status().isOk())
                .andReturn();

        Set<Long> appointmentIds = responseAppointmentIds(result);
        assertEquals(
                Set.of(firstOwnAppointment.getId(), secondOwnAppointment.getId()),
                appointmentIds);
        assertFalse(appointmentIds.contains(otherPatientsAppointment.getId()));
    }

    @Test
    void providerListUsesProviderRecordIdRatherThanUserId() throws Exception {
        saveUser(UserRole.PATIENT, "ID space offset");
        BookingFixture providerFixture = createFixture();
        BookingFixture otherProviderFixture = createFixture();
        Appointment firstOwnAppointment = saveScheduledAppointment(
                providerFixture,
                providerFixture.firstPatient(),
                providerFixture.startAt());
        Appointment secondOwnAppointment = saveScheduledAppointment(
                providerFixture,
                providerFixture.secondPatient(),
                providerFixture.startAt().plusSeconds(7_200));
        Appointment otherProvidersAppointment = saveScheduledAppointment(
                otherProviderFixture,
                otherProviderFixture.firstPatient(),
                otherProviderFixture.startAt());
        assertNotEquals(
                providerFixture.providerUser().getId(),
                providerFixture.provider().getId());

        MvcResult result = performAppointmentList(providerFixture.providerUser())
                .andExpect(status().isOk())
                .andReturn();

        Set<Long> appointmentIds = responseAppointmentIds(result);
        assertEquals(
                Set.of(firstOwnAppointment.getId(), secondOwnAppointment.getId()),
                appointmentIds);
        assertFalse(appointmentIds.contains(otherProvidersAppointment.getId()));
    }

    @Test
    void receptionistListContainsAllAppointments() throws Exception {
        BookingFixture firstFixture = createFixture();
        BookingFixture secondFixture = createFixture();
        Appointment firstAppointment = saveScheduledAppointment(
                firstFixture, firstFixture.firstPatient(), firstFixture.startAt());
        Appointment secondAppointment = saveScheduledAppointment(
                secondFixture, secondFixture.secondPatient(), secondFixture.startAt());
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");

        MvcResult result = performAppointmentList(receptionist)
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(
                Set.of(firstAppointment.getId(), secondAppointment.getId()),
                responseAppointmentIds(result));
    }

    @Test
    void adminListContainsAllAppointments() throws Exception {
        BookingFixture firstFixture = createFixture();
        BookingFixture secondFixture = createFixture();
        Appointment firstAppointment = saveScheduledAppointment(
                firstFixture, firstFixture.firstPatient(), firstFixture.startAt());
        Appointment secondAppointment = saveScheduledAppointment(
                secondFixture, secondFixture.secondPatient(), secondFixture.startAt());
        User admin = saveUser(UserRole.ADMIN, "Administrator");

        MvcResult result = performAppointmentList(admin)
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(
                Set.of(firstAppointment.getId(), secondAppointment.getId()),
                responseAppointmentIds(result));
    }

    @Test
    void emptyAppointmentScopeReturnsEmptyList() throws Exception {
        BookingFixture fixture = createFixture();

        performAppointmentList(fixture.firstPatient())
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void appointmentListDoesNotRequireCsrfToken() throws Exception {
        BookingFixture fixture = createFixture();
        saveScheduledAppointment(fixture, fixture.firstPatient(), fixture.startAt());

        performAppointmentList(fixture.firstPatient())
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedAppointmentListIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/appointments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void appointmentListIncludesEveryStatusInPatientScope() throws Exception {
        BookingFixture fixture = createFixture();
        User caller = fixture.firstPatient();
        Instant firstStartAt = fixture.startAt();
        Appointment scheduled = saveAppointment(
                fixture, caller, firstStartAt, AppointmentStatus.SCHEDULED);
        Appointment cancelled = saveAppointment(
                fixture,
                caller,
                firstStartAt.plusSeconds(7_200),
                AppointmentStatus.CANCELLED);
        Appointment completed = saveAppointment(
                fixture,
                caller,
                firstStartAt.plusSeconds(14_400),
                AppointmentStatus.COMPLETED);
        Appointment noShow = saveAppointment(
                fixture,
                caller,
                firstStartAt.plusSeconds(21_600),
                AppointmentStatus.NO_SHOW);

        MvcResult result = performAppointmentList(caller)
                .andExpect(status().isOk())
                .andReturn();
        List<AppointmentResponse> appointments = responseAppointments(result);

        assertEquals(
                Set.of(
                        scheduled.getId(),
                        cancelled.getId(),
                        completed.getId(),
                        noShow.getId()),
                Set.copyOf(appointments.stream().map(AppointmentResponse::id).toList()));
        assertEquals(
                Set.of(
                        AppointmentStatus.SCHEDULED.name(),
                        AppointmentStatus.CANCELLED.name(),
                        AppointmentStatus.COMPLETED.name(),
                        AppointmentStatus.NO_SHOW.name()),
                Set.copyOf(appointments.stream().map(AppointmentResponse::status).toList()));
    }

    @Test
    void providerWithoutProviderRecordUsesExistingIllegalStateMapping() throws Exception {
        User providerWithoutRecord = saveUser(UserRole.PROVIDER, "Provider without record");

        performAppointmentList(providerWithoutRecord)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE"))
                .andExpect(jsonPath("$.message").value("An internal error occurred"));
    }

    private ResultActions performAppointmentList(User caller) throws Exception {
        return mockMvc.perform(get("/api/appointments")
                .with(user(new AuthenticatedUser(caller))));
    }

    private ResultActions performAppointmentGet(User caller, Long appointmentId)
            throws Exception {
        return mockMvc.perform(get("/api/appointments/{id}", appointmentId)
                .with(user(new AuthenticatedUser(caller))));
    }

    private List<AppointmentResponse> responseAppointments(MvcResult result) throws Exception {
        return objectMapper.readerForListOf(AppointmentResponse.class)
                .readValue(result.getResponse().getContentAsByteArray());
    }

    private Set<Long> responseAppointmentIds(MvcResult result) throws Exception {
        return Set.copyOf(responseAppointments(result).stream()
                .map(AppointmentResponse::id)
                .toList());
    }

    private ResultActions performAppointmentAction(
            User caller,
            Long appointmentId,
            String action,
            boolean includeCsrf) throws Exception {
        var request = post("/api/appointments/{id}/{action}", appointmentId, action)
                .with(user(new AuthenticatedUser(caller)));
        if (includeCsrf) {
            request.with(csrf());
        }
        return mockMvc.perform(request);
    }

    private ResultActions performBooking(
            User caller,
            Long requestedPatientId,
            BookingFixture fixture,
            boolean includeCsrf) throws Exception {
        var request = post("/api/appointments")
                .with(user(new AuthenticatedUser(caller)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson(requestedPatientId, fixture));
        if (includeCsrf) {
            request.with(csrf());
        }
        return mockMvc.perform(request);
    }

    private ResultActions performCancel(
            User caller,
            Long appointmentId,
            String requestBody,
            boolean includeCsrf) throws Exception {
        var request = post("/api/appointments/{id}/cancel", appointmentId)
                .with(user(new AuthenticatedUser(caller)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody);
        if (includeCsrf) {
            request.with(csrf());
        }
        return mockMvc.perform(request);
    }

    private ResultActions performReschedule(
            User caller,
            Long appointmentId,
            BookingFixture fixture,
            Instant newStartAt,
            boolean includeCsrf) throws Exception {
        var request = post("/api/appointments/{id}/reschedule", appointmentId)
                .with(user(new AuthenticatedUser(caller)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(rescheduleRequestJson(fixture, newStartAt));
        if (includeCsrf) {
            request.with(csrf());
        }
        return mockMvc.perform(request);
    }

    private String requestJson(Long requestedPatientId, BookingFixture fixture) throws Exception {
        return objectMapper.writeValueAsString(new BookAppointmentRequest(
                requestedPatientId,
                fixture.provider().getId(),
                fixture.appointmentType().getId(),
                fixture.startAt()));
    }

    private String rescheduleRequestJson(BookingFixture fixture, Instant newStartAt)
            throws Exception {
        return objectMapper.writeValueAsString(new RescheduleAppointmentRequest(
                fixture.provider().getId(),
                fixture.appointmentType().getId(),
                newStartAt));
    }

    private Long responseAppointmentId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .get("id")
                .longValue();
    }

    private Appointment saveScheduledAppointment(
            BookingFixture fixture, User patient, Instant startAt) {
        Appointment appointment = new Appointment();
        appointment.setPatientId(patient.getId());
        appointment.setProviderId(fixture.provider().getId());
        appointment.setAppointmentTypeId(fixture.appointmentType().getId());
        appointment.setStartAt(startAt);
        appointment.setEndAt(startAt.plusSeconds(3_600));
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        return appointmentRepository.saveAndFlush(appointment);
    }

    private Appointment saveAppointment(
            BookingFixture fixture,
            User patient,
            Instant startAt,
            AppointmentStatus status) {
        Appointment appointment = saveScheduledAppointment(fixture, patient, startAt);
        appointment.setStatus(status);
        if (status == AppointmentStatus.CANCELLED) {
            appointment.setCancellationReason(CancellationReason.PATIENT_CANCELLED);
        }
        return appointmentRepository.saveAndFlush(appointment);
    }

    private BookingFixture createFixture() {
        long number = SEQUENCE.incrementAndGet();
        LocalDate appointmentDate = LocalDate.of(2040, 1, 9).plusDays(number);
        Instant startAt = appointmentDate.atTime(12, 0)
                .atZone(CLINIC_TIME_ZONE)
                .toInstant();

        Specialty specialty = new Specialty();
        specialty.setName("Appointment API Specialty " + number);
        Specialty savedSpecialty = specialtyRepository.saveAndFlush(specialty);

        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName("Appointment API Type " + number);
        appointmentType.setDurationMinutes(60);
        appointmentType.setSpecialtyId(savedSpecialty.getId());
        appointmentType.setActive(true);
        AppointmentType savedAppointmentType =
                appointmentTypeRepository.saveAndFlush(appointmentType);

        User providerUser = saveUser(UserRole.PROVIDER, "Provider");
        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(savedSpecialty.getId());
        provider.setLicenseNumber("APPOINTMENT-API-" + number);
        Provider savedProvider = providerRepository.saveAndFlush(provider);

        ProviderSchedule schedule = new ProviderSchedule();
        schedule.setProviderId(savedProvider.getId());
        schedule.setDayOfWeek(appointmentDate.getDayOfWeek());
        schedule.setStartTime(LocalTime.of(8, 0));
        schedule.setEndTime(LocalTime.of(18, 0));
        schedule.setActive(true);
        providerScheduleRepository.saveAndFlush(schedule);

        User firstPatient = saveUser(UserRole.PATIENT, "First Patient");
        User secondPatient = saveUser(UserRole.PATIENT, "Second Patient");
        return new BookingFixture(
                firstPatient,
                secondPatient,
                providerUser,
                savedProvider,
                savedAppointmentType,
                startAt);
    }

    private User saveUser(UserRole role, String displayName) {
        long number = SEQUENCE.incrementAndGet();
        User user = new User();
        user.setEmail("appointment-api-" + role.name().toLowerCase()
                + "-" + number + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName(displayName);
        return userRepository.saveAndFlush(user);
    }

    private record BookingFixture(
            User firstPatient,
            User secondPatient,
            User providerUser,
            Provider provider,
            AppointmentType appointmentType,
            Instant startAt) {
    }
}

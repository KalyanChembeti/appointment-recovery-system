package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.entity.Specialty;
import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.CancellationReason;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.domain.enums.TimeOfDayPreference;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.web.dto.AcceptOfferRequest;
import com.recoverysystem.web.dto.AcceptOfferResponse;
import com.recoverysystem.web.dto.SlotOfferResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
class OfferControllerTest {

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
    private AppointmentRepository appointmentRepository;

    @Autowired
    private WaitlistEntryRepository waitlistEntryRepository;

    @Autowired
    private RecoveryJobRepository recoveryJobRepository;

    @Autowired
    private SlotOfferRepository slotOfferRepository;

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
    void patientCanAcceptOwnOfferedOffer() throws Exception {
        OfferFixture fixture = createOfferFixture(
                SlotOfferStatus.OFFERED, Instant.now().plusSeconds(3_600));

        MvcResult result = performAccept(
                        fixture.patient(),
                        fixture.slotOffer().getId(),
                        new AcceptOfferRequest(null),
                        true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotOfferId").value(fixture.slotOffer().getId()))
                .andExpect(jsonPath("$.status").value(SlotOfferStatus.ACCEPTED.name()))
                .andExpect(jsonPath("$.patientId").value(fixture.patient().getId()))
                .andExpect(jsonPath("$.providerId").value(fixture.offeredProvider().getId()))
                .andExpect(jsonPath("$.appointmentTypeId")
                        .value(fixture.appointmentType().getId()))
                .andExpect(jsonPath("$.startAt").value(fixture.offeredStartAt().toString()))
                .andExpect(jsonPath("$.endAt")
                        .value(fixture.offeredStartAt().plusSeconds(3_600).toString()))
                .andReturn();

        AcceptOfferResponse response = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), AcceptOfferResponse.class);
        assertNotNull(response.appointmentId());
        Appointment persisted = appointmentRepository
                .findById(response.appointmentId())
                .orElseThrow();
        assertEquals(fixture.patient().getId(), persisted.getPatientId());
        assertEquals(fixture.offeredProvider().getId(), persisted.getProviderId());
        assertEquals(fixture.appointmentType().getId(), persisted.getAppointmentTypeId());
        assertEquals(fixture.offeredStartAt(), persisted.getStartAt());
        assertEquals(fixture.offeredStartAt().plusSeconds(3_600), persisted.getEndAt());
        assertEquals(AppointmentStatus.SCHEDULED, persisted.getStatus());
    }

    @Test
    void receptionistCanAcceptOnBehalfWithReceptionistAuditActor() throws Exception {
        OfferFixture fixture = createOfferFixture(
                SlotOfferStatus.OFFERED, Instant.now().plusSeconds(3_600));
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");
        long auditMarker = latestAuditId();

        MvcResult result = performAccept(
                        receptionist,
                        fixture.slotOffer().getId(),
                        new AcceptOfferRequest(fixture.patient().getId()),
                        true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientId").value(fixture.patient().getId()))
                .andReturn();

        AcceptOfferResponse response = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), AcceptOfferResponse.class);
        Appointment persisted = appointmentRepository
                .findById(response.appointmentId())
                .orElseThrow();
        assertEquals(fixture.patient().getId(), persisted.getPatientId());
        assertNotEquals(receptionist.getId(), persisted.getPatientId());

        List<AuditLog> audits = auditsAfter(auditMarker);
        assertFalse(audits.isEmpty());
        assertTrue(audits.stream().anyMatch(audit ->
                "SlotOffer".equals(audit.getEntityType())
                        && fixture.slotOffer().getId().equals(audit.getEntityId())
                        && "ACCEPT".equals(audit.getAction())));
        assertTrue(audits.stream()
                .allMatch(audit -> audit.getActorType() == ActorType.USER));
        assertTrue(audits.stream()
                .allMatch(audit -> receptionist.getId().equals(audit.getActorUserId())));
        assertTrue(audits.stream()
                .noneMatch(audit -> fixture.patient().getId().equals(audit.getActorUserId())));
    }

    @Test
    void patientCannotAcceptAnotherPatientsOffer() throws Exception {
        OfferFixture fixture = createOfferFixture(
                SlotOfferStatus.OFFERED, Instant.now().plusSeconds(3_600));
        User otherPatient = saveUser(UserRole.PATIENT, "Other Patient");

        performAccept(
                        otherPatient,
                        fixture.slotOffer().getId(),
                        new AcceptOfferRequest(null),
                        true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OFFER_ACCEPTANCE_OWNERSHIP"));

        assertEquals(
                SlotOfferStatus.OFFERED,
                slotOfferRepository.findById(fixture.slotOffer().getId())
                        .orElseThrow()
                        .getStatus());
        assertEquals(0L, auditLogRepository.count());
    }

    @Test
    void patientCanDeclineOwnOffer() throws Exception {
        OfferFixture fixture = createOfferFixture(
                SlotOfferStatus.OFFERED, Instant.now().plusSeconds(3_600));

        performDecline(fixture.patient(), fixture.slotOffer().getId(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fixture.slotOffer().getId()))
                .andExpect(jsonPath("$.recoveryJobId")
                        .value(fixture.recoveryJob().getId()))
                .andExpect(jsonPath("$.waitlistEntryId")
                        .value(fixture.waitlistEntry().getId()))
                .andExpect(jsonPath("$.status").value(SlotOfferStatus.DECLINED.name()))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.acceptedAt").doesNotExist())
                .andExpect(jsonPath("$.providerId")
                        .value(fixture.sourceAppointment().getProviderId()))
                .andExpect(jsonPath("$.appointmentTypeId")
                        .value(fixture.sourceAppointment().getAppointmentTypeId()))
                .andExpect(jsonPath("$.startAt")
                        .value(fixture.sourceAppointment().getStartAt().toString()))
                .andExpect(jsonPath("$.endAt")
                        .value(fixture.sourceAppointment().getEndAt().toString()));

        SlotOffer persisted = slotOfferRepository
                .findById(fixture.slotOffer().getId())
                .orElseThrow();
        assertEquals(SlotOfferStatus.DECLINED, persisted.getStatus());
    }

    @Test
    void patientCannotDeclineAnotherPatientsOffer() throws Exception {
        OfferFixture fixture = createOfferFixture(
                SlotOfferStatus.OFFERED, Instant.now().plusSeconds(3_600));
        User otherPatient = saveUser(UserRole.PATIENT, "Other Patient");

        performDecline(otherPatient, fixture.slotOffer().getId(), true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OFFER_ACCEPTANCE_OWNERSHIP"));

        SlotOffer persisted = slotOfferRepository
                .findById(fixture.slotOffer().getId())
                .orElseThrow();
        assertEquals(SlotOfferStatus.OFFERED, persisted.getStatus());
        assertEquals(0L, auditLogRepository.count());
    }

    @Test
    void receptionistCanDeclineAnyPatientsOfferWithReceptionistAuditActor() throws Exception {
        OfferFixture fixture = createOfferFixture(
                SlotOfferStatus.OFFERED, Instant.now().plusSeconds(3_600));
        User receptionist = saveUser(UserRole.RECEPTIONIST, "Receptionist");

        performDecline(receptionist, fixture.slotOffer().getId(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(SlotOfferStatus.DECLINED.name()));

        AuditLog audit = auditLogRepository.findAll().getFirst();
        assertEquals(ActorType.USER, audit.getActorType());
        assertEquals(receptionist.getId(), audit.getActorUserId());
    }

    @Test
    void providerCannotAcceptOrDeclineOffers() throws Exception {
        OfferFixture fixture = createOfferFixture(
                SlotOfferStatus.OFFERED, Instant.now().plusSeconds(3_600));

        performAccept(
                        fixture.offeredProviderUser(),
                        fixture.slotOffer().getId(),
                        new AcceptOfferRequest(fixture.patient().getId()),
                        true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROVIDER_ACTION_NOT_PERMITTED"));
        performDecline(fixture.offeredProviderUser(), fixture.slotOffer().getId(), true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROVIDER_ACTION_NOT_PERMITTED"));
    }

    @Test
    void unauthenticatedOfferRequestsAreUnauthorized() throws Exception {
        OfferFixture fixture = createOfferFixture(
                SlotOfferStatus.OFFERED, Instant.now().plusSeconds(3_600));

        mockMvc.perform(get("/api/slot-offers"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/slot-offers/{id}/accept", fixture.slotOffer().getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AcceptOfferRequest(null))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/slot-offers/{id}/decline", fixture.slotOffer().getId())
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void offerActionsRequireCsrfTokens() throws Exception {
        OfferFixture fixture = createOfferFixture(
                SlotOfferStatus.OFFERED, Instant.now().plusSeconds(3_600));

        performAccept(
                        fixture.patient(),
                        fixture.slotOffer().getId(),
                        new AcceptOfferRequest(null),
                        false)
                .andExpect(status().isForbidden());
        performDecline(fixture.patient(), fixture.slotOffer().getId(), false)
                .andExpect(status().isForbidden());

        assertEquals(
                SlotOfferStatus.OFFERED,
                slotOfferRepository.findById(fixture.slotOffer().getId())
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void patientOfferListIncludesResolvedStatusAndExcludesOtherPatientsOffers()
            throws Exception {
        User caller = saveUser(UserRole.PATIENT, "Listing Patient");
        OfferFixture offered = createOfferFixture(
                caller, SlotOfferStatus.OFFERED, Instant.now().plusSeconds(3_600));
        OfferFixture declined = createOfferFixture(
                caller, SlotOfferStatus.DECLINED, Instant.now().plusSeconds(7_200));
        OfferFixture anotherPatientsOffer = createOfferFixture(
                SlotOfferStatus.OFFERED, Instant.now().plusSeconds(10_800));

        MvcResult result = performList(caller)
                .andExpect(status().isOk())
                .andReturn();
        List<SlotOfferResponse> offers = objectMapper.readerForListOf(SlotOfferResponse.class)
                .readValue(result.getResponse().getContentAsByteArray());

        assertEquals(
                Set.of(offered.slotOffer().getId(), declined.slotOffer().getId()),
                Set.copyOf(offers.stream().map(SlotOfferResponse::id).toList()));
        assertEquals(
                Set.of(SlotOfferStatus.OFFERED.name(), SlotOfferStatus.DECLINED.name()),
                Set.copyOf(offers.stream().map(SlotOfferResponse::status).toList()));
        assertFalse(offers.stream().anyMatch(offer ->
                offer.id().equals(anotherPatientsOffer.slotOffer().getId())));
    }

    @Test
    void patientOfferListIncludesSourceAppointmentSlotDetails() throws Exception {
        OfferFixture fixture = createOfferFixture(
                SlotOfferStatus.OFFERED, Instant.now().plusSeconds(3_600));

        MvcResult result = performList(fixture.patient())
                .andExpect(status().isOk())
                .andReturn();
        List<SlotOfferResponse> offers = objectMapper
                .readerForListOf(SlotOfferResponse.class)
                .readValue(result.getResponse().getContentAsByteArray());
        SlotOfferResponse response = offers.getFirst();

        assertEquals(fixture.sourceAppointment().getProviderId(), response.providerId());
        assertEquals(
                fixture.sourceAppointment().getAppointmentTypeId(),
                response.appointmentTypeId());
        assertEquals(fixture.sourceAppointment().getStartAt(), response.startAt());
        assertEquals(fixture.sourceAppointment().getEndAt(), response.endAt());
        assertNotEquals(fixture.oldAppointment().getProviderId(), response.providerId());
        assertNotEquals(fixture.oldAppointment().getStartAt(), response.startAt());
    }

    @Test
    void acceptingAlreadyAcceptedOfferUsesExistingConflictMapping() throws Exception {
        OfferFixture fixture = createOfferFixture(
                SlotOfferStatus.ACCEPTED, Instant.now().plusSeconds(3_600));

        performAccept(
                        fixture.patient(),
                        fixture.slotOffer().getId(),
                        new AcceptOfferRequest(null),
                        true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFER_ALREADY_ACCEPTED"));

        assertEquals(
                SlotOfferStatus.ACCEPTED,
                slotOfferRepository.findById(fixture.slotOffer().getId())
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void acceptingStaleOfferedOfferCommitsAggressiveExpiryThroughHttp() throws Exception {
        OfferFixture fixture = createOfferFixture(
                SlotOfferStatus.OFFERED, Instant.now().minusSeconds(60));

        performAccept(
                        fixture.patient(),
                        fixture.slotOffer().getId(),
                        new AcceptOfferRequest(null),
                        true)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("OFFER_EXPIRED"));

        SlotOffer persisted = slotOfferRepository
                .findById(fixture.slotOffer().getId())
                .orElseThrow();
        assertEquals(SlotOfferStatus.EXPIRED, persisted.getStatus());
        assertNull(persisted.getAcceptedAt());
        List<AuditLog> audits = auditLogRepository.findAll();
        assertTrue(audits.stream().anyMatch(audit ->
                "SlotOffer".equals(audit.getEntityType())
                        && fixture.slotOffer().getId().equals(audit.getEntityId())
                        && "EXPIRE".equals(audit.getAction())
                        && audit.getActorType() == ActorType.SYSTEM
                        && audit.getActorUserId() == null));
    }

    private ResultActions performList(User caller) throws Exception {
        return mockMvc.perform(get("/api/slot-offers")
                .with(user(new AuthenticatedUser(caller))));
    }

    private ResultActions performAccept(
            User caller,
            Long slotOfferId,
            AcceptOfferRequest requestBody,
            boolean includeCsrf) throws Exception {
        var request = post("/api/slot-offers/{id}/accept", slotOfferId)
                .with(user(new AuthenticatedUser(caller)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody));
        if (includeCsrf) {
            request.with(csrf());
        }
        return mockMvc.perform(request);
    }

    private ResultActions performDecline(
            User caller, Long slotOfferId, boolean includeCsrf) throws Exception {
        var request = post("/api/slot-offers/{id}/decline", slotOfferId)
                .with(user(new AuthenticatedUser(caller)));
        if (includeCsrf) {
            request.with(csrf());
        }
        return mockMvc.perform(request);
    }

    private OfferFixture createOfferFixture(
            SlotOfferStatus status, Instant expiresAt) {
        return createOfferFixture(
                saveUser(UserRole.PATIENT, "Offer Patient"), status, expiresAt);
    }

    private OfferFixture createOfferFixture(
            User patient, SlotOfferStatus status, Instant expiresAt) {
        long number = SEQUENCE.incrementAndGet();
        Specialty specialty = new Specialty();
        specialty.setName("Offer API Specialty " + number);
        Specialty savedSpecialty = specialtyRepository.saveAndFlush(specialty);

        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName("Offer API Type " + number);
        appointmentType.setDurationMinutes(60);
        appointmentType.setSpecialtyId(savedSpecialty.getId());
        appointmentType.setActive(true);
        AppointmentType savedAppointmentType =
                appointmentTypeRepository.saveAndFlush(appointmentType);

        User oldProviderUser = saveUser(UserRole.PROVIDER, "Old Provider");
        Provider oldProvider = saveProvider(
                oldProviderUser, savedSpecialty, "OFFER-OLD-" + number);
        User offeredProviderUser = saveUser(UserRole.PROVIDER, "Offered Provider");
        Provider offeredProvider = saveProvider(
                offeredProviderUser, savedSpecialty, "OFFER-NEW-" + number);

        Instant oldStartAt = Instant.parse("2060-01-01T14:00:00Z")
                .plusSeconds(number * 1_209_600);
        Instant offeredStartAt = oldStartAt.minusSeconds(172_800);
        Appointment oldAppointment = saveAppointment(
                patient,
                oldProvider,
                savedAppointmentType,
                oldStartAt,
                AppointmentStatus.SCHEDULED);
        Appointment sourceAppointment = saveAppointment(
                patient,
                offeredProvider,
                savedAppointmentType,
                offeredStartAt,
                AppointmentStatus.CANCELLED);

        WaitlistEntry waitlistEntry = new WaitlistEntry();
        waitlistEntry.setPatientId(patient.getId());
        waitlistEntry.setCurrentAppointmentId(oldAppointment.getId());
        waitlistEntry.setAppointmentTypeId(savedAppointmentType.getId());
        LocalDate offeredDate = LocalDate.ofInstant(offeredStartAt, ZoneOffset.UTC);
        waitlistEntry.setEarliestAppointmentDate(offeredDate.minusDays(1));
        waitlistEntry.setLatestAppointmentDate(offeredDate.plusDays(1));
        waitlistEntry.setPreferredTimeOfDay(TimeOfDayPreference.ANY);
        waitlistEntry.setStatus(WaitlistEntryStatus.ACTIVE);
        WaitlistEntry savedWaitlistEntry =
                waitlistEntryRepository.saveAndFlush(waitlistEntry);

        RecoveryJob recoveryJob = new RecoveryJob();
        recoveryJob.setSourceAppointmentId(sourceAppointment.getId());
        recoveryJob.setStatus(RecoveryJobStatus.OPEN);
        RecoveryJob savedRecoveryJob = recoveryJobRepository.saveAndFlush(recoveryJob);

        SlotOffer slotOffer = new SlotOffer();
        slotOffer.setRecoveryJobId(savedRecoveryJob.getId());
        slotOffer.setWaitlistEntryId(savedWaitlistEntry.getId());
        slotOffer.setStatus(status);
        slotOffer.setExpiresAt(expiresAt);
        if (status == SlotOfferStatus.ACCEPTED) {
            slotOffer.setAcceptedAt(Instant.now());
        }
        SlotOffer savedSlotOffer = slotOfferRepository.saveAndFlush(slotOffer);

        return new OfferFixture(
                patient,
                offeredProviderUser,
                offeredProvider,
                savedAppointmentType,
                oldAppointment,
                sourceAppointment,
                savedWaitlistEntry,
                savedRecoveryJob,
                savedSlotOffer,
                offeredStartAt);
    }

    private Provider saveProvider(User user, Specialty specialty, String licenseNumber) {
        Provider provider = new Provider();
        provider.setUserId(user.getId());
        provider.setSpecialtyId(specialty.getId());
        provider.setLicenseNumber(licenseNumber);
        return providerRepository.saveAndFlush(provider);
    }

    private Appointment saveAppointment(
            User patient,
            Provider provider,
            AppointmentType appointmentType,
            Instant startAt,
            AppointmentStatus status) {
        Appointment appointment = new Appointment();
        appointment.setPatientId(patient.getId());
        appointment.setProviderId(provider.getId());
        appointment.setAppointmentTypeId(appointmentType.getId());
        appointment.setStartAt(startAt);
        appointment.setEndAt(startAt.plusSeconds(3_600));
        appointment.setStatus(status);
        if (status == AppointmentStatus.CANCELLED) {
            appointment.setCancellationReason(CancellationReason.PATIENT_CANCELLED);
        }
        return appointmentRepository.saveAndFlush(appointment);
    }

    private User saveUser(UserRole role, String displayName) {
        long number = SEQUENCE.incrementAndGet();
        User user = new User();
        user.setEmail("offer-api-" + role.name().toLowerCase()
                + "-" + number + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName(displayName);
        return userRepository.saveAndFlush(user);
    }

    private long latestAuditId() {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM audit_log", Long.class);
    }

    private List<AuditLog> auditsAfter(long auditId) {
        return auditLogRepository.findAll().stream()
                .filter(audit -> audit.getId() > auditId)
                .toList();
    }

    private record OfferFixture(
            User patient,
            User offeredProviderUser,
            Provider offeredProvider,
            AppointmentType appointmentType,
            Appointment oldAppointment,
            Appointment sourceAppointment,
            WaitlistEntry waitlistEntry,
            RecoveryJob recoveryJob,
            SlotOffer slotOffer,
            Instant offeredStartAt) {
    }
}

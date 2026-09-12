package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.CancellationReason;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.exception.OfferAcceptanceOwnershipException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import com.recoverysystem.service.OfferAcceptanceOrchestrator;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class OfferAcceptanceOnBehalfTest {

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
    private OfferAcceptanceOrchestrator offerAcceptanceOrchestrator;

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

    @Test
    void receptionistCanAcceptOfferForThePatientWhoOwnsIt() {
        OfferFixture fixture = createOfferFixture();
        Long receptionistId = insertUser("on-behalf-receptionist", "RECEPTIONIST");
        assertNotEquals(fixture.patientId(), receptionistId);
        long auditMarker = latestAuditId();

        Appointment newAppointment = offerAcceptanceOrchestrator.acceptOffer(
                fixture.slotOfferId(), fixture.patientId(), receptionistId);

        Appointment savedAppointment =
                appointmentRepository.findById(newAppointment.getId()).orElseThrow();
        assertEquals(AppointmentStatus.SCHEDULED, savedAppointment.getStatus());
        assertEquals(fixture.patientId(), savedAppointment.getPatientId());
        assertNotEquals(receptionistId, savedAppointment.getPatientId());
        assertEquals(fixture.offeredProviderId(), savedAppointment.getProviderId());
        assertEquals(fixture.appointmentTypeId(), savedAppointment.getAppointmentTypeId());

        Appointment oldAppointment =
                appointmentRepository.findById(fixture.oldAppointmentId()).orElseThrow();
        assertEquals(AppointmentStatus.CANCELLED, oldAppointment.getStatus());
        assertEquals(CancellationReason.RESCHEDULED, oldAppointment.getCancellationReason());
        assertEquals(savedAppointment.getId(), oldAppointment.getReplacedByAppointmentId());

        WaitlistEntry waitlistEntry =
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow();
        assertEquals(WaitlistEntryStatus.FULFILLED, waitlistEntry.getStatus());

        SlotOffer slotOffer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        assertEquals(SlotOfferStatus.ACCEPTED, slotOffer.getStatus());
        assertNotNull(slotOffer.getAcceptedAt());

        RecoveryJob recoveryJob =
                recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        assertEquals(RecoveryJobStatus.FILLED, recoveryJob.getStatus());
        assertNotNull(recoveryJob.getFilledAt());

        List<AuditLog> newAudits = auditsAfter(auditMarker);
        assertEquals(6, newAudits.size());
        assertTrue(newAudits.stream()
                .allMatch(audit -> audit.getActorType() == ActorType.USER));
        assertTrue(newAudits.stream()
                .allMatch(audit -> receptionistId.equals(audit.getActorUserId())));
        assertTrue(newAudits.stream()
                .noneMatch(audit -> fixture.patientId().equals(audit.getActorUserId())));
    }

    @Test
    void receptionistCannotAcceptOfferForPatientWhoDoesNotOwnIt() {
        OfferFixture fixture = createOfferFixture();
        Long receptionistId = insertUser("wrong-owner-receptionist", "RECEPTIONIST");
        Long otherPatientId = insertUser("wrong-offer-patient", "PATIENT");
        long appointmentCount = appointmentRepository.count();
        long auditMarker = latestAuditId();

        OfferAcceptanceOwnershipException exception = assertThrows(
                OfferAcceptanceOwnershipException.class,
                () -> offerAcceptanceOrchestrator.acceptOffer(
                        fixture.slotOfferId(), otherPatientId, receptionistId));

        assertTrue(exception.getMessage().contains(fixture.patientId().toString()));
        assertTrue(exception.getMessage().contains(otherPatientId.toString()));
        assertEquals(appointmentCount, appointmentRepository.count());
        assertEquals(
                AppointmentStatus.SCHEDULED,
                appointmentRepository.findById(fixture.oldAppointmentId()).orElseThrow().getStatus());
        assertEquals(
                WaitlistEntryStatus.ACTIVE,
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow().getStatus());
        assertEquals(
                SlotOfferStatus.OFFERED,
                slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow().getStatus());
        assertEquals(
                RecoveryJobStatus.OPEN,
                recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow().getStatus());
        assertTrue(auditsAfter(auditMarker).isEmpty());
    }

    private OfferFixture createOfferFixture() {
        long number = SEQUENCE.incrementAndGet();
        Long specialtyId = jdbcTemplate.queryForObject(
                "INSERT INTO specialty (name) VALUES (?) RETURNING id",
                Long.class,
                "On Behalf Specialty " + number);
        Long appointmentTypeId = jdbcTemplate.queryForObject(
                "INSERT INTO appointment_type "
                        + "(name, duration_minutes, specialty_id) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "On Behalf Type " + number,
                60,
                specialtyId);
        Long patientId = insertUser("on-behalf-patient-" + number, "PATIENT");
        Long oldProviderId = insertProvider(specialtyId, "old-" + number);
        Long offeredProviderId = insertProvider(specialtyId, "offered-" + number);
        Instant oldStart = Instant.parse("2060-01-01T14:00:00Z")
                .plusSeconds(number * 604_800);
        Instant offeredStart = oldStart.plusSeconds(172_800);
        Long oldAppointmentId = insertAppointment(
                patientId,
                oldProviderId,
                appointmentTypeId,
                oldStart,
                AppointmentStatus.SCHEDULED);
        Long offeredAppointmentId = insertAppointment(
                patientId,
                offeredProviderId,
                appointmentTypeId,
                offeredStart,
                AppointmentStatus.CANCELLED);
        Long waitlistEntryId = insertWaitlistEntry(
                patientId, oldAppointmentId, appointmentTypeId);
        Long recoveryJobId = jdbcTemplate.queryForObject(
                "INSERT INTO recovery_job (source_appointment_id, status) "
                        + "VALUES (?, 'OPEN') RETURNING id",
                Long.class,
                offeredAppointmentId);
        Long slotOfferId = jdbcTemplate.queryForObject(
                "INSERT INTO slot_offer "
                        + "(recovery_job_id, waitlist_entry_id, status, expires_at) "
                        + "VALUES (?, ?, 'OFFERED', ?) RETURNING id",
                Long.class,
                recoveryJobId,
                waitlistEntryId,
                Timestamp.from(Instant.now().plusSeconds(3600)));

        return new OfferFixture(
                patientId,
                appointmentTypeId,
                offeredProviderId,
                oldAppointmentId,
                waitlistEntryId,
                recoveryJobId,
                slotOfferId);
    }

    private Long insertAppointment(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Instant startAt,
            AppointmentStatus status) {
        String cancellationReason = status == AppointmentStatus.CANCELLED
                ? CancellationReason.PATIENT_CANCELLED.name()
                : null;
        return jdbcTemplate.queryForObject(
                "INSERT INTO appointment "
                        + "(patient_id, provider_id, appointment_type_id, start_at, end_at, "
                        + "status, cancellation_reason) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                patientId,
                providerId,
                appointmentTypeId,
                Timestamp.from(startAt),
                Timestamp.from(startAt.plusSeconds(3600)),
                status.name(),
                cancellationReason);
    }

    private Long insertWaitlistEntry(
            Long patientId, Long appointmentId, Long appointmentTypeId) {
        LocalDate startDate = LocalDate.of(2060, 1, 1)
                .plusDays(SEQUENCE.incrementAndGet());
        return jdbcTemplate.queryForObject(
                "INSERT INTO waitlist_entry "
                        + "(patient_id, current_appointment_id, appointment_type_id, "
                        + "earliest_appointment_date, latest_appointment_date, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE') RETURNING id",
                Long.class,
                patientId,
                appointmentId,
                appointmentTypeId,
                Date.valueOf(startDate),
                Date.valueOf(startDate.plusDays(7)));
    }

    private Long insertProvider(Long specialtyId, String name) {
        Long providerUserId = insertUser("on-behalf-provider-" + name, "PROVIDER");
        return jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "ON-BEHALF-" + name);
    }

    private Long insertUser(String emailPrefix, String role) {
        long number = SEQUENCE.incrementAndGet();
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, role) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                emailPrefix + "-" + number + "@example.com",
                "test-password-hash",
                role);
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
            Long patientId,
            Long appointmentTypeId,
            Long offeredProviderId,
            Long oldAppointmentId,
            Long waitlistEntryId,
            Long recoveryJobId,
            Long slotOfferId) {
    }
}

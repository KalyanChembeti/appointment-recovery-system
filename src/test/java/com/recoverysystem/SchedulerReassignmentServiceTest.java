package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.exception.AppointmentTypeSpecialtyMismatchException;
import com.recoverysystem.exception.OfferAlreadyAcceptedException;
import com.recoverysystem.exception.OfferAlreadyResolvedException;
import com.recoverysystem.exception.OfferExpiredException;
import com.recoverysystem.exception.PatientDoubleBookedException;
import com.recoverysystem.exception.ProviderDoubleBookedException;
import com.recoverysystem.exception.ProviderUnavailableException;
import com.recoverysystem.exception.RecoveryJobNotOpenException;
import com.recoverysystem.exception.SlotOfferNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import com.recoverysystem.service.SchedulerReassignmentService;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
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
class SchedulerReassignmentServiceTest {

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
    private SchedulerReassignmentService schedulerReassignmentService;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private RecoveryJobRepository recoveryJobRepository;

    @Autowired
    private SlotOfferRepository slotOfferRepository;

    @Autowired
    private WaitlistEntryRepository waitlistEntryRepository;

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
    void nullAppointmentTypeDefaultsToSourceTypeAndCreatesThreeAudits() {
        ReassignmentFixture fixture = createFixture();
        long auditMarker = latestAuditId();

        Appointment result = schedulerReassignmentService.reassignSlot(
                fixture.slotOfferId(),
                fixture.replacementPatientId(),
                null,
                fixture.receptionistId());

        Appointment savedAppointment = appointmentRepository.findById(result.getId()).orElseThrow();
        assertEquals(fixture.replacementPatientId(), savedAppointment.getPatientId());
        assertEquals(fixture.providerId(), savedAppointment.getProviderId());
        assertEquals(fixture.sourceAppointmentTypeId(), savedAppointment.getAppointmentTypeId());
        assertEquals(fixture.offeredStartAt(), savedAppointment.getStartAt());
        assertEquals(fixture.offeredStartAt().plus(60, ChronoUnit.MINUTES),
                savedAppointment.getEndAt());
        assertEquals(AppointmentStatus.SCHEDULED, savedAppointment.getStatus());

        SlotOffer offer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        assertEquals(SlotOfferStatus.CANCELLED, offer.getStatus());
        RecoveryJob job = recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        assertEquals(RecoveryJobStatus.FILLED, job.getStatus());
        assertNotNull(job.getFilledAt());

        List<AuditLog> audits = auditsAfter(auditMarker);
        assertEquals(3, audits.size());
        assertAudit(audits, "SlotOffer", fixture.slotOfferId(), "CANCEL", "STAFF_OVERRIDE");
        assertAudit(audits, "RecoveryJob", fixture.recoveryJobId(), "FILLED", null);
        assertAudit(audits, "Appointment", savedAppointment.getId(), "CREATE", null);
        assertTrue(audits.stream().allMatch(audit -> audit.getActorType() == ActorType.USER));
        assertTrue(audits.stream()
                .allMatch(audit -> fixture.receptionistId().equals(audit.getActorUserId())));
    }

    @Test
    void suppliedAppointmentTypeUsesItsOwnDurationForEndTime() {
        ReassignmentFixture fixture = createFixture();
        Long ninetyMinuteTypeId = insertAppointmentType(
                fixture.specialtyId(), "Ninety Minute Reassignment", 90);

        Appointment result = schedulerReassignmentService.reassignSlot(
                fixture.slotOfferId(),
                fixture.replacementPatientId(),
                ninetyMinuteTypeId,
                fixture.receptionistId());

        Appointment savedAppointment = appointmentRepository.findById(result.getId()).orElseThrow();
        assertEquals(ninetyMinuteTypeId, savedAppointment.getAppointmentTypeId());
        assertEquals(
                fixture.offeredStartAt().plus(90, ChronoUnit.MINUTES),
                savedAppointment.getEndAt());
        assertNotEquals(fixture.sourceEndAt(), savedAppointment.getEndAt());
    }

    @Test
    void appointmentTypeWithDifferentSpecialtyIsRejectedWithoutChanges() {
        ReassignmentFixture fixture = createFixture();
        Long otherSpecialtyId = insertSpecialty("Different Reassignment Specialty");
        Long wrongTypeId = insertAppointmentType(otherSpecialtyId, "Wrong Specialty Type", 60);
        StateBeforeCall beforeCall = captureState(fixture);

        assertThrows(
                AppointmentTypeSpecialtyMismatchException.class,
                () -> schedulerReassignmentService.reassignSlot(
                        fixture.slotOfferId(),
                        fixture.replacementPatientId(),
                        wrongTypeId,
                        fixture.receptionistId()));

        assertStateUnchanged(fixture, beforeCall);
    }

    @Test
    void activeProviderBlockIsRejectedWithoutChanges() {
        assertProviderBlockIsRejected(ProviderUnavailabilityStatus.ACTIVE);
    }

    @Test
    void pendingProviderBlockIsAlsoRejectedWithoutChanges() {
        assertProviderBlockIsRejected(ProviderUnavailabilityStatus.PENDING);
    }

    @Test
    void acceptedOfferIsRejectedWithoutChanges() {
        ReassignmentFixture fixture = createFixture(
                SlotOfferStatus.ACCEPTED,
                RecoveryJobStatus.OPEN,
                Instant.now().plusSeconds(3600));
        StateBeforeCall beforeCall = captureState(fixture);

        assertThrows(
                OfferAlreadyAcceptedException.class,
                () -> callServiceWithSourceType(fixture));

        assertStateUnchanged(fixture, beforeCall);
    }

    @Test
    void declinedOfferIsRejectedAsAlreadyResolved() {
        assertResolvedOfferIsRejected(SlotOfferStatus.DECLINED);
    }

    @Test
    void cancelledOfferIsRejectedAsAlreadyResolved() {
        assertResolvedOfferIsRejected(SlotOfferStatus.CANCELLED);
    }

    @Test
    void staleOfferedSlotIsExpiredAndCommittedBeforeException() {
        ReassignmentFixture fixture = createFixture(
                SlotOfferStatus.OFFERED,
                RecoveryJobStatus.OPEN,
                Instant.now().minusSeconds(60));
        StateBeforeCall beforeCall = captureState(fixture);

        assertThrows(OfferExpiredException.class, () -> callServiceWithSourceType(fixture));

        SlotOffer savedOffer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        assertEquals(SlotOfferStatus.EXPIRED, savedOffer.getStatus());
        assertTrue(savedOffer.getUpdatedAt().isAfter(beforeCall.offerUpdatedAt()));
        assertEquals(beforeCall.appointmentCount(), appointmentRepository.count());
        assertEquals(RecoveryJobStatus.OPEN,
                recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow().getStatus());
        assertEquals(WaitlistEntryStatus.ACTIVE,
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow().getStatus());
        List<AuditLog> audits = auditsAfter(beforeCall.auditMarker());
        assertEquals(1, audits.size());
        assertAudit(audits, "SlotOffer", fixture.slotOfferId(), "EXPIRE", null);
        assertEquals(ActorType.SYSTEM, audits.getFirst().getActorType());
        assertNull(audits.getFirst().getActorUserId());
    }

    @Test
    void recoveryJobThatIsNotOpenIsRejectedWithoutChanges() {
        ReassignmentFixture fixture = createFixture(
                SlotOfferStatus.OFFERED,
                RecoveryJobStatus.SUPPRESSED,
                Instant.now().plusSeconds(3600));
        StateBeforeCall beforeCall = captureState(fixture);

        assertThrows(
                RecoveryJobNotOpenException.class,
                () -> callServiceWithSourceType(fixture));

        assertStateUnchanged(fixture, beforeCall);
    }

    @Test
    void nonexistentOfferIsRejected() {
        long appointmentCount = appointmentRepository.count();
        long auditMarker = latestAuditId();

        assertThrows(
                SlotOfferNotFoundException.class,
                () -> schedulerReassignmentService.reassignSlot(
                        Long.MAX_VALUE, Long.MAX_VALUE, null, Long.MAX_VALUE));

        assertEquals(appointmentCount, appointmentRepository.count());
        assertTrue(auditsAfter(auditMarker).isEmpty());
    }

    @Test
    void patientConflictRollsBackAndLeavesOfferOffered() {
        ReassignmentFixture fixture = createFixture();
        Long otherProviderId = insertProvider(fixture.specialtyId(), "patient-conflict-provider");
        insertAppointment(
                fixture.replacementPatientId(),
                otherProviderId,
                fixture.sourceAppointmentTypeId(),
                fixture.offeredStartAt().plusSeconds(300),
                fixture.sourceEndAt().minusSeconds(300),
                AppointmentStatus.SCHEDULED);
        StateBeforeCall beforeCall = captureState(fixture);

        assertThrows(
                PatientDoubleBookedException.class,
                () -> callServiceWithSourceType(fixture));

        assertStateUnchanged(fixture, beforeCall);
        assertEquals(SlotOfferStatus.OFFERED,
                slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow().getStatus());
    }

    @Test
    void providerConflictRollsBackAndLeavesOfferOffered() {
        ReassignmentFixture fixture = createFixture();
        Long otherPatientId = insertUser("provider-conflict-patient", "PATIENT");
        insertAppointment(
                otherPatientId,
                fixture.providerId(),
                fixture.sourceAppointmentTypeId(),
                fixture.offeredStartAt().plusSeconds(300),
                fixture.sourceEndAt().minusSeconds(300),
                AppointmentStatus.SCHEDULED);
        StateBeforeCall beforeCall = captureState(fixture);

        assertThrows(
                ProviderDoubleBookedException.class,
                () -> callServiceWithSourceType(fixture));

        assertStateUnchanged(fixture, beforeCall);
        assertEquals(SlotOfferStatus.OFFERED,
                slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow().getStatus());
    }

    @Test
    void successfulReassignmentDoesNotChangeOriginalWaitlistEntry() {
        ReassignmentFixture fixture = createFixture();
        WaitlistEntry beforeCall =
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow();
        Instant originalUpdatedAt = beforeCall.getUpdatedAt();

        callServiceWithSourceType(fixture);

        WaitlistEntry afterCall =
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow();
        assertEquals(WaitlistEntryStatus.ACTIVE, afterCall.getStatus());
        assertEquals(originalUpdatedAt, afterCall.getUpdatedAt());
        assertEquals(beforeCall.getPatientId(), afterCall.getPatientId());
        assertEquals(beforeCall.getCurrentAppointmentId(), afterCall.getCurrentAppointmentId());
        assertEquals(beforeCall.getAppointmentTypeId(), afterCall.getAppointmentTypeId());
    }

    private void assertProviderBlockIsRejected(ProviderUnavailabilityStatus status) {
        ReassignmentFixture fixture = createFixture();
        insertProviderBlock(fixture, status);
        StateBeforeCall beforeCall = captureState(fixture);

        assertThrows(
                ProviderUnavailableException.class,
                () -> callServiceWithSourceType(fixture));

        assertStateUnchanged(fixture, beforeCall);
    }

    private void assertResolvedOfferIsRejected(SlotOfferStatus status) {
        ReassignmentFixture fixture = createFixture(
                status,
                RecoveryJobStatus.OPEN,
                Instant.now().plusSeconds(3600));
        StateBeforeCall beforeCall = captureState(fixture);

        OfferAlreadyResolvedException exception = assertThrows(
                OfferAlreadyResolvedException.class,
                () -> callServiceWithSourceType(fixture));

        assertTrue(exception.getMessage().contains(status.name()));
        assertStateUnchanged(fixture, beforeCall);
    }

    private Appointment callServiceWithSourceType(ReassignmentFixture fixture) {
        return schedulerReassignmentService.reassignSlot(
                fixture.slotOfferId(),
                fixture.replacementPatientId(),
                null,
                fixture.receptionistId());
    }

    private StateBeforeCall captureState(ReassignmentFixture fixture) {
        SlotOffer offer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        RecoveryJob job = recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        WaitlistEntry entry =
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow();
        return new StateBeforeCall(
                appointmentRepository.count(),
                latestAuditId(),
                offer.getStatus(),
                offer.getUpdatedAt(),
                job.getStatus(),
                job.getFilledAt(),
                job.getUpdatedAt(),
                entry.getStatus(),
                entry.getUpdatedAt());
    }

    private void assertStateUnchanged(
            ReassignmentFixture fixture, StateBeforeCall beforeCall) {
        SlotOffer offer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        RecoveryJob job = recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        WaitlistEntry entry =
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow();
        assertEquals(beforeCall.appointmentCount(), appointmentRepository.count());
        assertEquals(beforeCall.offerStatus(), offer.getStatus());
        assertEquals(beforeCall.offerUpdatedAt(), offer.getUpdatedAt());
        assertEquals(beforeCall.jobStatus(), job.getStatus());
        assertEquals(beforeCall.jobFilledAt(), job.getFilledAt());
        assertEquals(beforeCall.jobUpdatedAt(), job.getUpdatedAt());
        assertEquals(beforeCall.entryStatus(), entry.getStatus());
        assertEquals(beforeCall.entryUpdatedAt(), entry.getUpdatedAt());
        assertTrue(auditsAfter(beforeCall.auditMarker()).isEmpty());
    }

    private ReassignmentFixture createFixture() {
        return createFixture(
                SlotOfferStatus.OFFERED,
                RecoveryJobStatus.OPEN,
                Instant.now().plusSeconds(3600));
    }

    private ReassignmentFixture createFixture(
            SlotOfferStatus offerStatus,
            RecoveryJobStatus jobStatus,
            Instant expiresAt) {
        long number = SEQUENCE.incrementAndGet();
        Long specialtyId = insertSpecialty("Reassignment Specialty " + number);
        Long sourceTypeId = insertAppointmentType(
                specialtyId, "Reassignment Source Type " + number, 60);
        Long originalPatientId = insertUser("reassignment-original-" + number, "PATIENT");
        Long replacementPatientId = insertUser("reassignment-replacement-" + number, "PATIENT");
        Long receptionistId = insertUser("reassignment-receptionist-" + number, "RECEPTIONIST");
        Long providerId = insertProvider(specialtyId, "offered-" + number);
        Long anchorProviderId = insertProvider(specialtyId, "anchor-" + number);
        Instant offeredStartAt = Instant.parse("2060-01-05T15:00:00Z")
                .plus(number * 7, ChronoUnit.DAYS);
        Instant sourceEndAt = offeredStartAt.plus(60, ChronoUnit.MINUTES);
        insertWorkingHours(providerId, offeredStartAt);

        Long sourceAppointmentId = insertAppointment(
                originalPatientId,
                providerId,
                sourceTypeId,
                offeredStartAt,
                sourceEndAt,
                AppointmentStatus.CANCELLED);
        Instant anchorStart = offeredStartAt.plus(7, ChronoUnit.DAYS);
        Long anchorAppointmentId = insertAppointment(
                originalPatientId,
                anchorProviderId,
                sourceTypeId,
                anchorStart,
                anchorStart.plus(60, ChronoUnit.MINUTES),
                AppointmentStatus.SCHEDULED);
        Long waitlistEntryId = insertWaitlistEntry(
                originalPatientId, anchorAppointmentId, sourceTypeId, offeredStartAt);
        Long recoveryJobId = jdbcTemplate.queryForObject(
                "INSERT INTO recovery_job (source_appointment_id, status) "
                        + "VALUES (?, ?) RETURNING id",
                Long.class,
                sourceAppointmentId,
                jobStatus.name());
        Long slotOfferId = jdbcTemplate.queryForObject(
                "INSERT INTO slot_offer "
                        + "(recovery_job_id, waitlist_entry_id, status, expires_at) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                recoveryJobId,
                waitlistEntryId,
                offerStatus.name(),
                Timestamp.from(expiresAt));

        return new ReassignmentFixture(
                specialtyId,
                sourceTypeId,
                replacementPatientId,
                receptionistId,
                providerId,
                recoveryJobId,
                slotOfferId,
                waitlistEntryId,
                offeredStartAt,
                sourceEndAt);
    }

    private Long insertSpecialty(String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO specialty (name) VALUES (?) RETURNING id", Long.class, name);
    }

    private Long insertAppointmentType(Long specialtyId, String name, int durationMinutes) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO appointment_type "
                        + "(name, duration_minutes, specialty_id) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                name,
                durationMinutes,
                specialtyId);
    }

    private Long insertProvider(Long specialtyId, String name) {
        Long providerUserId = insertUser("reassignment-provider-" + name, "PROVIDER");
        return jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "REASSIGNMENT-" + name);
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

    private Long insertAppointment(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Instant startAt,
            Instant endAt,
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
                Timestamp.from(endAt),
                status.name(),
                cancellationReason);
    }

    private Long insertWaitlistEntry(
            Long patientId,
            Long appointmentId,
            Long appointmentTypeId,
            Instant offeredStartAt) {
        LocalDate offeredDate = offeredStartAt.atZone(CLINIC_TIME_ZONE).toLocalDate();
        return jdbcTemplate.queryForObject(
                "INSERT INTO waitlist_entry "
                        + "(patient_id, current_appointment_id, appointment_type_id, "
                        + "earliest_appointment_date, latest_appointment_date, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE') RETURNING id",
                Long.class,
                patientId,
                appointmentId,
                appointmentTypeId,
                Date.valueOf(offeredDate.minusDays(7)),
                Date.valueOf(offeredDate.plusDays(7)));
    }

    private void insertWorkingHours(Long providerId, Instant offeredStartAt) {
        jdbcTemplate.update(
                "INSERT INTO provider_schedule "
                        + "(provider_id, day_of_week, start_time, end_time, is_active) "
                        + "VALUES (?, ?, ?, ?, true)",
                providerId,
                offeredStartAt.atZone(CLINIC_TIME_ZONE).getDayOfWeek().name(),
                Time.valueOf(LocalTime.of(8, 0)),
                Time.valueOf(LocalTime.of(18, 0)));
    }

    private void insertProviderBlock(
            ReassignmentFixture fixture, ProviderUnavailabilityStatus status) {
        jdbcTemplate.update(
                "INSERT INTO provider_unavailability "
                        + "(provider_id, start_at, end_at, status) VALUES (?, ?, ?, ?)",
                fixture.providerId(),
                Timestamp.from(fixture.offeredStartAt().minusSeconds(300)),
                Timestamp.from(fixture.sourceEndAt().plusSeconds(300)),
                status.name());
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

    private void assertAudit(
            List<AuditLog> audits,
            String entityType,
            Long entityId,
            String action,
            String reason) {
        AuditLog audit = audits.stream()
                .filter(item -> entityType.equals(item.getEntityType()))
                .filter(item -> entityId.equals(item.getEntityId()))
                .filter(item -> action.equals(item.getAction()))
                .findFirst()
                .orElseThrow();
        assertEquals(reason, audit.getReason());
    }

    private record ReassignmentFixture(
            Long specialtyId,
            Long sourceAppointmentTypeId,
            Long replacementPatientId,
            Long receptionistId,
            Long providerId,
            Long recoveryJobId,
            Long slotOfferId,
            Long waitlistEntryId,
            Instant offeredStartAt,
            Instant sourceEndAt) {
    }

    private record StateBeforeCall(
            long appointmentCount,
            long auditMarker,
            SlotOfferStatus offerStatus,
            Instant offerUpdatedAt,
            RecoveryJobStatus jobStatus,
            Instant jobFilledAt,
            Instant jobUpdatedAt,
            WaitlistEntryStatus entryStatus,
            Instant entryUpdatedAt) {
    }
}

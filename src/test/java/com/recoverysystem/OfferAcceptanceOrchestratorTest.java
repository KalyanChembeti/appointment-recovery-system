package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.recoverysystem.domain.enums.TimeOfDayPreference;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.exception.AppointmentNotScheduledException;
import com.recoverysystem.exception.OfferAcceptanceOwnershipException;
import com.recoverysystem.exception.OfferAlreadyAcceptedException;
import com.recoverysystem.exception.OfferExpiredException;
import com.recoverysystem.exception.PatientDoubleBookedException;
import com.recoverysystem.exception.ProviderIntervalOccupiedException;
import com.recoverysystem.exception.ProviderUnavailableException;
import com.recoverysystem.exception.RecoveryJobNotOpenException;
import com.recoverysystem.exception.SiblingAlreadyFulfilledException;
import com.recoverysystem.exception.SlotOfferNotFoundException;
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
class OfferAcceptanceOrchestratorTest {

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
    void acceptsOfferAndMovesPatientToNewAppointment() {
        AcceptanceFixture fixture = createFixture();
        long auditMarker = latestAuditId();

        Appointment newAppointment = offerAcceptanceOrchestrator.acceptOffer(
                fixture.slotOfferId(), fixture.patientId(), fixture.patientId());

        assertSuccessfulAcceptance(fixture, newAppointment);
        List<AuditLog> audits = auditsAfter(auditMarker);
        assertEquals(6, audits.size());
        assertAudit(audits, "Appointment", newAppointment.getId(), "CREATE", null);
        assertAudit(audits, "Appointment", fixture.oldAppointmentId(), "CANCEL", null);
        assertAudit(audits, "WaitlistEntry", fixture.waitlistEntryId(), "FULFILL", null);
        assertAudit(audits, "SlotOffer", fixture.slotOfferId(), "ACCEPT", null);
        assertAudit(audits, "RecoveryJob", fixture.recoveryJobId(), "FILLED", null);
        Long oldIntervalJobId = recoveryJobsFor(fixture.oldAppointmentId()).getFirst().getId();
        assertAudit(audits, "RecoveryJob", oldIntervalJobId, "CREATE", null);
        assertTrue(audits.stream().allMatch(audit -> audit.getActorType() == ActorType.USER));
        assertTrue(audits.stream()
                .allMatch(audit -> fixture.patientId().equals(audit.getActorUserId())));
    }

    @Test
    void acceptsOfferAndCleansAllRelatedOffersAndSibling() {
        AcceptanceFixture fixture = createFixture();
        Long siblingEntryId = insertWaitlistEntry(
                fixture.patientId(),
                fixture.oldAppointmentId(),
                fixture.appointmentTypeId(),
                WaitlistEntryStatus.ACTIVE);

        Long categoryBOfferId = insertExtraOffer(
                fixture.patientId(),
                fixture.offeredProviderId(),
                fixture.appointmentTypeId(),
                fixture.waitlistEntryId(),
                fixture.offeredStart().minusSeconds(259200));
        Long categoryCOfferId = insertExtraOffer(
                fixture.patientId(),
                fixture.offeredProviderId(),
                fixture.appointmentTypeId(),
                siblingEntryId,
                fixture.offeredStart().minusSeconds(172800));

        Long otherAppointmentId = insertAppointment(
                fixture.patientId(),
                fixture.oldProviderId(),
                fixture.appointmentTypeId(),
                fixture.offeredStart().plusSeconds(864000),
                AppointmentStatus.SCHEDULED);
        Long otherEntryId = insertWaitlistEntry(
                fixture.patientId(),
                otherAppointmentId,
                fixture.appointmentTypeId(),
                WaitlistEntryStatus.ACTIVE);
        Long categoryDOfferId = insertExtraOffer(
                fixture.patientId(),
                fixture.offeredProviderId(),
                fixture.appointmentTypeId(),
                otherEntryId,
                fixture.offeredStart().plusSeconds(900));
        long auditMarker = latestAuditId();

        Appointment newAppointment = offerAcceptanceOrchestrator.acceptOffer(
                fixture.slotOfferId(), fixture.patientId(), fixture.patientId());

        assertSuccessfulAcceptance(fixture, newAppointment);
        assertEquals(
                WaitlistEntryStatus.REMOVED,
                waitlistEntryRepository.findById(siblingEntryId).orElseThrow().getStatus());
        assertEquals(
                WaitlistEntryStatus.ACTIVE,
                waitlistEntryRepository.findById(otherEntryId).orElseThrow().getStatus());
        for (Long offerId : List.of(
                categoryBOfferId, categoryCOfferId, categoryDOfferId)) {
            assertEquals(
                    SlotOfferStatus.CANCELLED,
                    slotOfferRepository.findById(offerId).orElseThrow().getStatus());
        }

        List<AuditLog> audits = auditsAfter(auditMarker);
        assertEquals(10, audits.size());
        assertAudit(audits, "Appointment", newAppointment.getId(), "CREATE", null);
        assertAudit(audits, "Appointment", fixture.oldAppointmentId(), "CANCEL", null);
        assertAudit(audits, "WaitlistEntry", fixture.waitlistEntryId(), "FULFILL", null);
        assertAudit(audits, "WaitlistEntry", siblingEntryId, "REMOVE", null);
        assertAudit(audits, "SlotOffer", fixture.slotOfferId(), "ACCEPT", null);
        assertAudit(audits, "RecoveryJob", fixture.recoveryJobId(), "FILLED", null);
        Long oldIntervalJobId = recoveryJobsFor(fixture.oldAppointmentId()).getFirst().getId();
        assertAudit(audits, "RecoveryJob", oldIntervalJobId, "CREATE", null);
        for (Long offerId : List.of(
                categoryBOfferId, categoryCOfferId, categoryDOfferId)) {
            assertAudit(
                    audits,
                    "SlotOffer",
                    offerId,
                    "CANCEL",
                    "SUPERSEDED_BY_ACCEPTANCE");
        }
    }

    @Test
    void activeDestinationBlockRejectsWithoutChanges() {
        assertDestinationBlockRejects(ProviderUnavailabilityStatus.ACTIVE);
    }

    @Test
    void pendingDestinationBlockRejectsWithoutChanges() {
        assertDestinationBlockRejects(ProviderUnavailabilityStatus.PENDING);
    }

    @Test
    void oldAppointmentMustStillBeScheduled() {
        AcceptanceFixture fixture = createFixture(
                AppointmentStatus.COMPLETED,
                RecoveryJobStatus.OPEN,
                SlotOfferStatus.OFFERED,
                Instant.now().plusSeconds(3600));
        long auditMarker = latestAuditId();

        assertThrows(
                AppointmentNotScheduledException.class,
                () -> offerAcceptanceOrchestrator.acceptOffer(
                        fixture.slotOfferId(), fixture.patientId(), fixture.patientId()));

        assertEquals(
                AppointmentStatus.COMPLETED,
                appointmentRepository.findById(fixture.oldAppointmentId()).orElseThrow().getStatus());
        assertFixtureState(
                fixture,
                WaitlistEntryStatus.ACTIVE,
                SlotOfferStatus.OFFERED,
                RecoveryJobStatus.OPEN);
        assertTrue(auditsAfter(auditMarker).isEmpty());
    }

    @Test
    void wrongPatientIsRejectedWithoutChanges() {
        AcceptanceFixture fixture = createFixture();
        Long otherPatientId = insertUser("acceptance-wrong-patient-" + SEQUENCE.incrementAndGet(),
                "PATIENT");
        long appointmentCount = appointmentRepository.count();
        long auditMarker = latestAuditId();

        assertThrows(
                OfferAcceptanceOwnershipException.class,
                () -> offerAcceptanceOrchestrator.acceptOffer(
                        fixture.slotOfferId(), otherPatientId, otherPatientId));

        assertEquals(appointmentCount, appointmentRepository.count());
        assertEquals(
                AppointmentStatus.SCHEDULED,
                appointmentRepository.findById(fixture.oldAppointmentId()).orElseThrow().getStatus());
        assertFixtureState(
                fixture,
                WaitlistEntryStatus.ACTIVE,
                SlotOfferStatus.OFFERED,
                RecoveryJobStatus.OPEN);
        assertTrue(auditsAfter(auditMarker).isEmpty());
    }

    @Test
    void recoveryJobMustStillBeOpen() {
        AcceptanceFixture fixture = createFixture(
                AppointmentStatus.SCHEDULED,
                RecoveryJobStatus.SUPPRESSED,
                SlotOfferStatus.OFFERED,
                Instant.now().plusSeconds(3600));
        long auditMarker = latestAuditId();

        RecoveryJobNotOpenException exception = assertThrows(
                RecoveryJobNotOpenException.class,
                () -> offerAcceptanceOrchestrator.acceptOffer(
                        fixture.slotOfferId(), fixture.patientId(), fixture.patientId()));

        assertTrue(exception.getMessage().contains("SUPPRESSED"));
        assertFixtureState(
                fixture,
                WaitlistEntryStatus.ACTIVE,
                SlotOfferStatus.OFFERED,
                RecoveryJobStatus.SUPPRESSED);
        assertTrue(auditsAfter(auditMarker).isEmpty());
    }

    @Test
    void alreadyAcceptedOfferExceptionPropagates() {
        AcceptanceFixture fixture = createFixture(
                AppointmentStatus.SCHEDULED,
                RecoveryJobStatus.OPEN,
                SlotOfferStatus.ACCEPTED,
                Instant.now().plusSeconds(3600));
        long auditMarker = latestAuditId();

        assertThrows(
                OfferAlreadyAcceptedException.class,
                () -> offerAcceptanceOrchestrator.acceptOffer(
                        fixture.slotOfferId(), fixture.patientId(), fixture.patientId()));

        assertFixtureState(
                fixture,
                WaitlistEntryStatus.ACTIVE,
                SlotOfferStatus.ACCEPTED,
                RecoveryJobStatus.OPEN);
        assertTrue(auditsAfter(auditMarker).isEmpty());
    }

    @Test
    void expiredOfferCommitsExpiryBeforeException() {
        AcceptanceFixture fixture = createFixture(
                AppointmentStatus.SCHEDULED,
                RecoveryJobStatus.OPEN,
                SlotOfferStatus.OFFERED,
                Instant.now().minusSeconds(60));
        long appointmentCount = appointmentRepository.count();
        long auditMarker = latestAuditId();

        assertThrows(
                OfferExpiredException.class,
                () -> offerAcceptanceOrchestrator.acceptOffer(
                        fixture.slotOfferId(), fixture.patientId(), fixture.patientId()));

        SlotOffer savedOffer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        RecoveryJob savedJob = recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        assertEquals(SlotOfferStatus.EXPIRED, savedOffer.getStatus());
        assertEquals(RecoveryJobStatus.OPEN, savedJob.getStatus());
        assertEquals(appointmentCount, appointmentRepository.count());
        assertEquals(
                AppointmentStatus.SCHEDULED,
                appointmentRepository.findById(fixture.oldAppointmentId()).orElseThrow().getStatus());
        List<AuditLog> audits = auditsAfter(auditMarker);
        assertEquals(1, audits.size());
        assertAudit(audits, "SlotOffer", fixture.slotOfferId(), "EXPIRE", null);
    }

    @Test
    void occupiedProviderCommitsOfferAndJobTerminalStates() {
        AcceptanceFixture fixture = createFixture();
        Long otherPatientId = insertUser("acceptance-occupied-patient-" + SEQUENCE.incrementAndGet(),
                "PATIENT");
        insertAppointment(
                otherPatientId,
                fixture.offeredProviderId(),
                fixture.appointmentTypeId(),
                fixture.offeredStart(),
                AppointmentStatus.SCHEDULED);
        long appointmentCount = appointmentRepository.count();
        long auditMarker = latestAuditId();

        assertThrows(
                ProviderIntervalOccupiedException.class,
                () -> offerAcceptanceOrchestrator.acceptOffer(
                        fixture.slotOfferId(), fixture.patientId(), fixture.patientId()));

        SlotOffer savedOffer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        RecoveryJob savedJob = recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        assertEquals(SlotOfferStatus.CANCELLED, savedOffer.getStatus());
        assertEquals(RecoveryJobStatus.FILLED, savedJob.getStatus());
        assertNotNull(savedJob.getFilledAt());
        assertEquals(appointmentCount, appointmentRepository.count());
        assertEquals(
                AppointmentStatus.SCHEDULED,
                appointmentRepository.findById(fixture.oldAppointmentId()).orElseThrow().getStatus());
        assertEquals(
                WaitlistEntryStatus.ACTIVE,
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow().getStatus());
        assertTrue(recoveryJobsFor(fixture.oldAppointmentId()).isEmpty());

        List<AuditLog> audits = auditsAfter(auditMarker);
        assertEquals(2, audits.size());
        assertAudit(
                audits,
                "SlotOffer",
                fixture.slotOfferId(),
                "CANCEL",
                "PROVIDER_INTERVAL_OCCUPIED");
        assertAudit(audits, "RecoveryJob", fixture.recoveryJobId(), "FILLED", null);
    }

    @Test
    void patientConflictRollsBackAcceptanceThenCancelsOfferSeparately() {
        AcceptanceFixture fixture = createFixture();
        insertAppointment(
                fixture.patientId(),
                fixture.oldProviderId(),
                fixture.appointmentTypeId(),
                fixture.offeredStart(),
                AppointmentStatus.SCHEDULED);
        long appointmentCount = appointmentRepository.count();
        long auditMarker = latestAuditId();

        assertThrows(
                PatientDoubleBookedException.class,
                () -> offerAcceptanceOrchestrator.acceptOffer(
                        fixture.slotOfferId(), fixture.patientId(), fixture.patientId()));

        Appointment oldAppointment =
                appointmentRepository.findById(fixture.oldAppointmentId()).orElseThrow();
        WaitlistEntry acceptedEntry =
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow();
        SlotOffer acceptedOffer =
                slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        RecoveryJob recoveryJob =
                recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        assertEquals(AppointmentStatus.SCHEDULED, oldAppointment.getStatus());
        assertEquals(WaitlistEntryStatus.ACTIVE, acceptedEntry.getStatus());
        assertEquals(SlotOfferStatus.CANCELLED, acceptedOffer.getStatus());
        assertNull(acceptedOffer.getAcceptedAt());
        assertEquals(RecoveryJobStatus.OPEN, recoveryJob.getStatus());
        assertEquals(appointmentCount, appointmentRepository.count());
        assertTrue(recoveryJobsFor(fixture.oldAppointmentId()).isEmpty());

        List<AuditLog> audits = auditsAfter(auditMarker);
        assertEquals(1, audits.size());
        assertAudit(
                audits,
                "SlotOffer",
                fixture.slotOfferId(),
                "CANCEL",
                "PATIENT_SCHEDULE_CONFLICT");
    }

    @Test
    void activeOldIntervalBlockSkipsRecoveryJob() {
        AcceptanceFixture fixture = createFixture();
        insertBlock(
                fixture.oldProviderId(),
                fixture.oldStart(),
                fixture.oldStart().plusSeconds(3600),
                ProviderUnavailabilityStatus.ACTIVE);

        Appointment newAppointment = offerAcceptanceOrchestrator.acceptOffer(
                fixture.slotOfferId(), fixture.patientId(), fixture.patientId());

        assertSuccessfulAcceptanceWithoutOldIntervalJob(fixture, newAppointment);
    }

    @Test
    void pendingOldIntervalBlockStillCreatesRecoveryJob() {
        AcceptanceFixture fixture = createFixture();
        insertBlock(
                fixture.oldProviderId(),
                fixture.oldStart(),
                fixture.oldStart().plusSeconds(3600),
                ProviderUnavailabilityStatus.PENDING);

        Appointment newAppointment = offerAcceptanceOrchestrator.acceptOffer(
                fixture.slotOfferId(), fixture.patientId(), fixture.patientId());

        assertSuccessfulAcceptance(fixture, newAppointment);
        assertEquals(1, recoveryJobsFor(fixture.oldAppointmentId()).size());
    }

    @Test
    void fulfilledSiblingIsRejected() {
        AcceptanceFixture fixture = createFixture();
        WaitlistEntry sibling = new WaitlistEntry();
        sibling.setPatientId(fixture.patientId());
        sibling.setCurrentAppointmentId(fixture.oldAppointmentId());
        sibling.setAppointmentTypeId(fixture.appointmentTypeId());
        sibling.setEarliestAppointmentDate(LocalDate.of(2060, 1, 1));
        sibling.setLatestAppointmentDate(LocalDate.of(2060, 1, 8));
        sibling.setPreferredTimeOfDay(TimeOfDayPreference.ANY);
        sibling.setStatus(WaitlistEntryStatus.FULFILLED);
        WaitlistEntry savedSibling = waitlistEntryRepository.save(sibling);
        long auditMarker = latestAuditId();

        assertThrows(
                SiblingAlreadyFulfilledException.class,
                () -> offerAcceptanceOrchestrator.acceptOffer(
                        fixture.slotOfferId(), fixture.patientId(), fixture.patientId()));

        assertFixtureState(
                fixture,
                WaitlistEntryStatus.ACTIVE,
                SlotOfferStatus.OFFERED,
                RecoveryJobStatus.OPEN);
        assertEquals(
                WaitlistEntryStatus.FULFILLED,
                waitlistEntryRepository.findById(savedSibling.getId()).orElseThrow().getStatus());
        assertTrue(auditsAfter(auditMarker).isEmpty());
    }

    @Test
    void auditCascadeUsesSystemOrUserActor() {
        AcceptanceFixture userFixture = createFixture();
        long userAuditMarker = latestAuditId();

        offerAcceptanceOrchestrator.acceptOffer(
                userFixture.slotOfferId(), userFixture.patientId(), userFixture.patientId());

        List<AuditLog> userAudits = auditsAfter(userAuditMarker);
        assertEquals(6, userAudits.size());
        assertTrue(userAudits.stream()
                .allMatch(audit -> audit.getActorType() == ActorType.USER));
        assertTrue(userAudits.stream()
                .allMatch(audit -> userFixture.patientId().equals(audit.getActorUserId())));

        AcceptanceFixture systemFixture = createFixture();
        long systemAuditMarker = latestAuditId();

        offerAcceptanceOrchestrator.acceptOffer(
                systemFixture.slotOfferId(), systemFixture.patientId(), null);

        List<AuditLog> systemAudits = auditsAfter(systemAuditMarker);
        assertEquals(6, systemAudits.size());
        assertTrue(systemAudits.stream()
                .allMatch(audit -> audit.getActorType() == ActorType.SYSTEM));
        assertTrue(systemAudits.stream().allMatch(audit -> audit.getActorUserId() == null));
    }

    @Test
    void missingSlotOfferIsRejected() {
        long appointmentCount = appointmentRepository.count();
        long auditCount = auditLogRepository.count();

        assertThrows(
                SlotOfferNotFoundException.class,
                () -> offerAcceptanceOrchestrator.acceptOffer(
                        Long.MAX_VALUE, Long.MAX_VALUE, null));

        assertEquals(appointmentCount, appointmentRepository.count());
        assertEquals(auditCount, auditLogRepository.count());
    }

    private void assertDestinationBlockRejects(ProviderUnavailabilityStatus status) {
        AcceptanceFixture fixture = createFixture();
        insertBlock(
                fixture.offeredProviderId(),
                fixture.offeredStart(),
                fixture.offeredStart().plusSeconds(3600),
                status);
        long appointmentCount = appointmentRepository.count();
        long auditMarker = latestAuditId();

        assertThrows(
                ProviderUnavailableException.class,
                () -> offerAcceptanceOrchestrator.acceptOffer(
                        fixture.slotOfferId(), fixture.patientId(), fixture.patientId()));

        assertEquals(appointmentCount, appointmentRepository.count());
        assertEquals(
                AppointmentStatus.SCHEDULED,
                appointmentRepository.findById(fixture.oldAppointmentId()).orElseThrow().getStatus());
        assertFixtureState(
                fixture,
                WaitlistEntryStatus.ACTIVE,
                SlotOfferStatus.OFFERED,
                RecoveryJobStatus.OPEN);
        assertTrue(auditsAfter(auditMarker).isEmpty());
    }

    private void assertSuccessfulAcceptance(
            AcceptanceFixture fixture, Appointment newAppointment) {
        Appointment savedNewAppointment =
                appointmentRepository.findById(newAppointment.getId()).orElseThrow();
        assertEquals(AppointmentStatus.SCHEDULED, savedNewAppointment.getStatus());
        assertEquals(fixture.patientId(), savedNewAppointment.getPatientId());
        assertEquals(fixture.offeredProviderId(), savedNewAppointment.getProviderId());
        assertEquals(fixture.appointmentTypeId(), savedNewAppointment.getAppointmentTypeId());
        assertEquals(fixture.offeredStart(), savedNewAppointment.getStartAt());
        assertEquals(fixture.offeredStart().plusSeconds(3600), savedNewAppointment.getEndAt());

        Appointment oldAppointment =
                appointmentRepository.findById(fixture.oldAppointmentId()).orElseThrow();
        assertEquals(AppointmentStatus.CANCELLED, oldAppointment.getStatus());
        assertEquals(CancellationReason.RESCHEDULED, oldAppointment.getCancellationReason());
        assertEquals(newAppointment.getId(), oldAppointment.getReplacedByAppointmentId());

        WaitlistEntry acceptedEntry =
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow();
        assertEquals(WaitlistEntryStatus.FULFILLED, acceptedEntry.getStatus());
        SlotOffer acceptedOffer =
                slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        assertEquals(SlotOfferStatus.ACCEPTED, acceptedOffer.getStatus());
        assertNotNull(acceptedOffer.getAcceptedAt());
        RecoveryJob filledJob =
                recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        assertEquals(RecoveryJobStatus.FILLED, filledJob.getStatus());
        assertNotNull(filledJob.getFilledAt());

        List<RecoveryJob> oldIntervalJobs = recoveryJobsFor(fixture.oldAppointmentId());
        assertEquals(1, oldIntervalJobs.size());
        assertEquals(RecoveryJobStatus.OPEN, oldIntervalJobs.getFirst().getStatus());
    }

    private void assertSuccessfulAcceptanceWithoutOldIntervalJob(
            AcceptanceFixture fixture, Appointment newAppointment) {
        Appointment savedNewAppointment =
                appointmentRepository.findById(newAppointment.getId()).orElseThrow();
        assertEquals(AppointmentStatus.SCHEDULED, savedNewAppointment.getStatus());
        assertEquals(
                AppointmentStatus.CANCELLED,
                appointmentRepository.findById(fixture.oldAppointmentId()).orElseThrow().getStatus());
        assertEquals(
                WaitlistEntryStatus.FULFILLED,
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow().getStatus());
        assertEquals(
                SlotOfferStatus.ACCEPTED,
                slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow().getStatus());
        assertEquals(
                RecoveryJobStatus.FILLED,
                recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow().getStatus());
        assertTrue(recoveryJobsFor(fixture.oldAppointmentId()).isEmpty());
    }

    private void assertFixtureState(
            AcceptanceFixture fixture,
            WaitlistEntryStatus entryStatus,
            SlotOfferStatus offerStatus,
            RecoveryJobStatus jobStatus) {
        assertEquals(
                entryStatus,
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow().getStatus());
        assertEquals(
                offerStatus,
                slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow().getStatus());
        assertEquals(
                jobStatus,
                recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow().getStatus());
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

    private AcceptanceFixture createFixture() {
        return createFixture(
                AppointmentStatus.SCHEDULED,
                RecoveryJobStatus.OPEN,
                SlotOfferStatus.OFFERED,
                Instant.now().plusSeconds(3600));
    }

    private AcceptanceFixture createFixture(
            AppointmentStatus oldAppointmentStatus,
            RecoveryJobStatus recoveryJobStatus,
            SlotOfferStatus slotOfferStatus,
            Instant expiresAt) {
        long number = SEQUENCE.incrementAndGet();
        Long specialtyId = jdbcTemplate.queryForObject(
                "INSERT INTO specialty (name) VALUES (?) RETURNING id",
                Long.class,
                "Offer Acceptance Specialty " + number);
        Long appointmentTypeId = jdbcTemplate.queryForObject(
                "INSERT INTO appointment_type "
                        + "(name, duration_minutes, specialty_id) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "Offer Acceptance Type " + number,
                60,
                specialtyId);
        Long patientId = insertUser("offer-acceptance-patient-" + number, "PATIENT");
        Long oldProviderId = insertProvider(specialtyId, "old-" + number);
        Long offeredProviderId = insertProvider(specialtyId, "offered-" + number);
        Instant oldStart = Instant.parse("2060-01-01T14:00:00Z")
                .plusSeconds(number * 604800);
        Instant offeredStart = oldStart.plusSeconds(172800);
        Long oldAppointmentId = insertAppointment(
                patientId,
                oldProviderId,
                appointmentTypeId,
                oldStart,
                oldAppointmentStatus);
        Long offeredAppointmentId = insertAppointment(
                patientId,
                offeredProviderId,
                appointmentTypeId,
                offeredStart,
                AppointmentStatus.CANCELLED);
        Long waitlistEntryId = insertWaitlistEntry(
                patientId,
                oldAppointmentId,
                appointmentTypeId,
                WaitlistEntryStatus.ACTIVE);
        Long recoveryJobId = insertRecoveryJob(offeredAppointmentId, recoveryJobStatus);
        Long slotOfferId = insertOffer(
                recoveryJobId, waitlistEntryId, slotOfferStatus, expiresAt);

        return new AcceptanceFixture(
                patientId,
                appointmentTypeId,
                oldProviderId,
                offeredProviderId,
                oldAppointmentId,
                waitlistEntryId,
                recoveryJobId,
                slotOfferId,
                oldStart,
                offeredStart);
    }

    private Long insertExtraOffer(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Long waitlistEntryId,
            Instant startAt) {
        Long sourceAppointmentId = insertAppointment(
                patientId,
                providerId,
                appointmentTypeId,
                startAt,
                AppointmentStatus.CANCELLED);
        Long recoveryJobId = insertRecoveryJob(sourceAppointmentId, RecoveryJobStatus.OPEN);
        return insertOffer(
                recoveryJobId,
                waitlistEntryId,
                SlotOfferStatus.OFFERED,
                Instant.now().plusSeconds(3600));
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
            Long patientId,
            Long appointmentId,
            Long appointmentTypeId,
            WaitlistEntryStatus status) {
        LocalDate startDate = LocalDate.of(2060, 1, 1).plusDays(SEQUENCE.incrementAndGet());
        return jdbcTemplate.queryForObject(
                "INSERT INTO waitlist_entry "
                        + "(patient_id, current_appointment_id, appointment_type_id, "
                        + "earliest_appointment_date, latest_appointment_date, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                patientId,
                appointmentId,
                appointmentTypeId,
                Date.valueOf(startDate),
                Date.valueOf(startDate.plusDays(7)),
                status.name());
    }

    private Long insertRecoveryJob(Long sourceAppointmentId, RecoveryJobStatus status) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO recovery_job (source_appointment_id, status) "
                        + "VALUES (?, ?) RETURNING id",
                Long.class,
                sourceAppointmentId,
                status.name());
    }

    private Long insertOffer(
            Long recoveryJobId,
            Long waitlistEntryId,
            SlotOfferStatus status,
            Instant expiresAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO slot_offer "
                        + "(recovery_job_id, waitlist_entry_id, status, expires_at) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                recoveryJobId,
                waitlistEntryId,
                status.name(),
                Timestamp.from(expiresAt));
    }

    private Long insertProvider(Long specialtyId, String name) {
        Long providerUserId = insertUser("offer-acceptance-provider-" + name, "PROVIDER");
        return jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "OFFER-ACCEPTANCE-" + name);
    }

    private void insertBlock(
            Long providerId,
            Instant startAt,
            Instant endAt,
            ProviderUnavailabilityStatus status) {
        jdbcTemplate.update(
                "INSERT INTO provider_unavailability (provider_id, start_at, end_at, status) "
                        + "VALUES (?, ?, ?, ?)",
                providerId,
                Timestamp.from(startAt),
                Timestamp.from(endAt),
                status.name());
    }

    private Long insertUser(String emailPrefix, String role) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, role) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                emailPrefix + "@example.com",
                "test-password-hash",
                role);
    }

    private List<RecoveryJob> recoveryJobsFor(Long appointmentId) {
        return recoveryJobRepository.findAll().stream()
                .filter(job -> appointmentId.equals(job.getSourceAppointmentId()))
                .toList();
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

    private record AcceptanceFixture(
            Long patientId,
            Long appointmentTypeId,
            Long oldProviderId,
            Long offeredProviderId,
            Long oldAppointmentId,
            Long waitlistEntryId,
            Long recoveryJobId,
            Long slotOfferId,
            Instant oldStart,
            Instant offeredStart) {
    }
}

package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.domain.enums.RecoveryWorkerOutcome;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.domain.enums.TimeOfDayPreference;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.service.RecoveryWorkerService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class RecoveryWorkerServiceTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ZoneId CLINIC_TIME_ZONE = ZoneId.of("America/New_York");
    private static final Instant ORDERING_TIME = Instant.parse("2060-01-01T12:00:00Z");

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
    private RecoveryWorkerService recoveryWorkerService;

    @SpyBean
    private RecoveryJobRepository recoveryJobRepository;

    @Autowired
    private SlotOfferRepository slotOfferRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearWorkflowData() {
        org.mockito.Mockito.reset(recoveryJobRepository);
        jdbcTemplate.execute("TRUNCATE TABLE audit_log, slot_offer, recovery_job, "
                + "waitlist_entry, appointment, provider_schedule, provider_unavailability, "
                + "provider, appointment_type, specialty, users RESTART IDENTITY CASCADE");
        jdbcTemplate.update("UPDATE scheduling_policy SET "
                + "minimum_recovery_lead_minutes = 30, offer_duration_minutes = 10");
    }

    @Test
    void noOpenJobsReturnsWithoutDatabaseChangesOrLockingRoutingRead()
            throws NoSuchMethodException {
        long rowsBefore = workflowRowCount();

        RecoveryWorkerOutcome outcome = recoveryWorkerService.attemptRecovery();

        assertEquals(RecoveryWorkerOutcome.NO_OPEN_JOBS, outcome);
        assertEquals(rowsBefore, workflowRowCount());
        assertNull(RecoveryJobRepository.class
                .getMethod("findOldestOpenJobIdsWithoutOfferedOffer", Pageable.class)
                .getAnnotation(Lock.class));
        Transactional transaction = RecoveryWorkerService.class
                .getDeclaredMethod("attemptRecovery")
                .getAnnotation(Transactional.class);
        assertNotNull(transaction);
        assertEquals(Isolation.READ_COMMITTED, transaction.isolation());
    }

    @Test
    void occupiedReleasedIntervalFillsJob() {
        JobFixture fixture = createJob(futureStart(), ORDERING_TIME);
        insertAppointment(
                insertUser("occupied-patient", "PATIENT"),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.startAt().plusSeconds(300),
                fixture.endAt().minusSeconds(300),
                AppointmentStatus.SCHEDULED);

        RecoveryWorkerOutcome outcome = recoveryWorkerService.attemptRecovery();

        RecoveryJob job = findJob(fixture.recoveryJobId());
        assertEquals(RecoveryWorkerOutcome.RELEASED_INTERVAL_OCCUPIED, outcome);
        assertEquals(RecoveryJobStatus.FILLED, job.getStatus());
        assertNotNull(job.getFilledAt());
        assertEquals(1, jobAudits(fixture.recoveryJobId()).size());
    }

    @Test
    void activeProviderBlockSuppressesJob() {
        JobFixture fixture = createJob(futureStart(), ORDERING_TIME);
        insertProviderBlock(fixture, ProviderUnavailabilityStatus.ACTIVE);

        RecoveryWorkerOutcome outcome = recoveryWorkerService.attemptRecovery();

        RecoveryJob job = findJob(fixture.recoveryJobId());
        assertEquals(RecoveryWorkerOutcome.PROVIDER_ACTIVELY_BLOCKED, outcome);
        assertEquals(RecoveryJobStatus.SUPPRESSED, job.getStatus());
        assertEquals("PROVIDER_BLOCK_ACTIVATED", job.getSuppressionReason());
    }

    @Test
    void pendingProviderBlockLeavesJobUnchanged() {
        JobFixture fixture = createJob(futureStart(), ORDERING_TIME);
        jdbcTemplate.update(
                "UPDATE recovery_job SET suppression_reason = ? WHERE id = ?",
                "keep this value",
                fixture.recoveryJobId());
        insertProviderBlock(fixture, ProviderUnavailabilityStatus.PENDING);
        RecoveryJob originalJob = findJob(fixture.recoveryJobId());
        Instant originalUpdatedAt = originalJob.getUpdatedAt();

        RecoveryWorkerOutcome outcome = recoveryWorkerService.attemptRecovery();

        RecoveryJob savedJob = findJob(fixture.recoveryJobId());
        assertEquals(RecoveryWorkerOutcome.PROVIDER_PENDING_BLOCKED, outcome);
        assertEquals(RecoveryJobStatus.OPEN, savedJob.getStatus());
        assertEquals("keep this value", savedJob.getSuppressionReason());
        assertNull(savedJob.getFilledAt());
        assertEquals(originalUpdatedAt, savedJob.getUpdatedAt());
        assertTrue(jobAudits(fixture.recoveryJobId()).isEmpty());
    }

    @Test
    void closedLeadTimeSuppressesJob() {
        JobFixture fixture = createJob(Instant.now().plusSeconds(60), ORDERING_TIME);

        RecoveryWorkerOutcome outcome = recoveryWorkerService.attemptRecovery();

        RecoveryJob job = findJob(fixture.recoveryJobId());
        assertEquals(RecoveryWorkerOutcome.LEAD_TIME_CLOSED, outcome);
        assertEquals(RecoveryJobStatus.SUPPRESSED, job.getStatus());
        assertEquals("LEAD_TIME_CLOSED", job.getSuppressionReason());
    }

    @Test
    void noEligibleCandidateExhaustsJobAndWritesAudit() {
        JobFixture fixture = createJob(futureStart(), ORDERING_TIME);

        RecoveryWorkerOutcome outcome = recoveryWorkerService.attemptRecovery();

        RecoveryJob job = findJob(fixture.recoveryJobId());
        assertEquals(RecoveryWorkerOutcome.NO_ELIGIBLE_CANDIDATE, outcome);
        assertEquals(RecoveryJobStatus.EXHAUSTED, job.getStatus());
        List<AuditLog> audits = jobAudits(fixture.recoveryJobId());
        assertEquals(1, audits.size());
        assertSystemAudit(audits.getFirst(), "RecoveryJob", fixture.recoveryJobId(), "EXHAUST");
    }

    @Test
    void eligibleCandidateCreatesOfferUsingPolicyDurationAndLeavesJobOpen() {
        jdbcTemplate.update("UPDATE scheduling_policy SET offer_duration_minutes = 37");
        JobFixture fixture = createJob(futureStart(), ORDERING_TIME);
        Candidate candidate = insertCandidate(
                fixture, ORDERING_TIME, TimeOfDayPreference.ANY, null);
        Instant beforeCall = Instant.now();

        RecoveryWorkerOutcome outcome = recoveryWorkerService.attemptRecovery();
        Instant afterCall = Instant.now();

        assertEquals(RecoveryWorkerOutcome.OFFER_CREATED, outcome);
        List<SlotOffer> offers = slotOfferRepository.findAll();
        assertEquals(1, offers.size());
        SlotOffer offer = offers.getFirst();
        assertEquals(fixture.recoveryJobId(), offer.getRecoveryJobId());
        assertEquals(candidate.waitlistEntryId(), offer.getWaitlistEntryId());
        assertEquals(SlotOfferStatus.OFFERED, offer.getStatus());
        assertFalse(offer.getExpiresAt().isBefore(beforeCall.plus(37, ChronoUnit.MINUTES)));
        assertFalse(offer.getExpiresAt().isAfter(afterCall.plus(37, ChronoUnit.MINUTES)));
        assertEquals(RecoveryJobStatus.OPEN, findJob(fixture.recoveryJobId()).getStatus());

        List<AuditLog> audits = offerAudits(offer.getId());
        assertEquals(1, audits.size());
        assertSystemAudit(audits.getFirst(), "SlotOffer", offer.getId(), "CREATE");
    }

    @Test
    void multipleCandidatesCreatesOfferForTopRankedCandidate() {
        JobFixture fixture = createJob(futureStart(), ORDERING_TIME);
        Candidate newerCandidate = insertCandidate(
                fixture,
                ORDERING_TIME.plusSeconds(3600),
                TimeOfDayPreference.ANY,
                null);
        Candidate olderCandidate = insertCandidate(
                fixture, ORDERING_TIME, TimeOfDayPreference.ANY, null);
        assertTrue(newerCandidate.waitlistEntryId() < olderCandidate.waitlistEntryId());

        RecoveryWorkerOutcome outcome = recoveryWorkerService.attemptRecovery();

        assertEquals(RecoveryWorkerOutcome.OFFER_CREATED, outcome);
        SlotOffer offer = slotOfferRepository.findAll().getFirst();
        assertEquals(olderCandidate.waitlistEntryId(), offer.getWaitlistEntryId());
    }

    @Test
    void oldestOpenJobIsProcessedFirst() {
        JobFixture newerJob = createJob(
                futureStart().plusSeconds(7200), ORDERING_TIME.plusSeconds(3600));
        JobFixture olderJob = createJob(futureStart(), ORDERING_TIME);
        assertTrue(newerJob.recoveryJobId() < olderJob.recoveryJobId());

        RecoveryWorkerOutcome outcome = recoveryWorkerService.attemptRecovery();

        assertEquals(RecoveryWorkerOutcome.NO_ELIGIBLE_CANDIDATE, outcome);
        assertEquals(RecoveryJobStatus.EXHAUSTED, findJob(olderJob.recoveryJobId()).getStatus());
        assertEquals(RecoveryJobStatus.OPEN, findJob(newerJob.recoveryJobId()).getStatus());
    }

    @Test
    void jobWithOfferedOfferIsSkippedForNextEligibleJob() {
        JobFixture jobWithOffer = createJob(futureStart(), ORDERING_TIME);
        Candidate firstCandidate = insertCandidate(
                jobWithOffer, ORDERING_TIME, TimeOfDayPreference.ANY, null);
        Long existingOfferId = insertSlotOffer(
                jobWithOffer.recoveryJobId(),
                firstCandidate.waitlistEntryId(),
                SlotOfferStatus.OFFERED);

        JobFixture eligibleJob = createJob(
                futureStart().plusSeconds(7200), ORDERING_TIME.plusSeconds(3600));
        Candidate eligibleCandidate = insertCandidate(
                eligibleJob, ORDERING_TIME, TimeOfDayPreference.ANY, null);

        RecoveryWorkerOutcome outcome = recoveryWorkerService.attemptRecovery();

        assertEquals(RecoveryWorkerOutcome.OFFER_CREATED, outcome);
        assertEquals(RecoveryJobStatus.OPEN, findJob(jobWithOffer.recoveryJobId()).getStatus());
        assertEquals(SlotOfferStatus.OFFERED,
                slotOfferRepository.findById(existingOfferId).orElseThrow().getStatus());
        List<SlotOffer> eligibleJobOffers = slotOfferRepository.findAll().stream()
                .filter(offer -> eligibleJob.recoveryJobId().equals(offer.getRecoveryJobId()))
                .toList();
        assertEquals(1, eligibleJobOffers.size());
        assertEquals(
                eligibleCandidate.waitlistEntryId(),
                eligibleJobOffers.getFirst().getWaitlistEntryId());
    }

    @Test
    void offeredSlotCreatedAfterRoutingReturnsOfferAlreadyExistsWithoutChanges()
            throws NoSuchMethodException {
        JobFixture fixture = createJob(futureStart(), ORDERING_TIME);
        Candidate candidate = insertCandidate(
                fixture, ORDERING_TIME, TimeOfDayPreference.ANY, null);
        Long existingOfferId = insertSlotOffer(
                fixture.recoveryJobId(),
                candidate.waitlistEntryId(),
                SlotOfferStatus.OFFERED);
        long offerCountBefore = slotOfferRepository.count();
        long auditCountBefore = auditLogRepository.count();

        org.mockito.Mockito.doReturn(List.of(fixture.recoveryJobId()))
                .when(recoveryJobRepository)
                .findOldestOpenJobIdsWithoutOfferedOffer(
                        org.mockito.ArgumentMatchers.any(Pageable.class));

        RecoveryWorkerOutcome outcome = recoveryWorkerService.attemptRecovery();

        assertEquals(RecoveryWorkerOutcome.OFFER_ALREADY_EXISTS_FOR_JOB, outcome);
        assertEquals(RecoveryJobStatus.OPEN, findJob(fixture.recoveryJobId()).getStatus());
        assertEquals(offerCountBefore, slotOfferRepository.count());
        assertEquals(auditCountBefore, auditLogRepository.count());
        assertEquals(
                SlotOfferStatus.OFFERED,
                slotOfferRepository.findById(existingOfferId).orElseThrow().getStatus());
        assertNull(SlotOfferRepository.class
                .getMethod(
                        "existsByRecoveryJobIdAndStatus",
                        Long.class,
                        SlotOfferStatus.class)
                .getAnnotation(Lock.class));
    }

    @Test
    void concurrentWorkersCreateOneOfferAndReturnHandledRaceOutcome() throws Exception {
        JobFixture fixture = createJob(futureStart(), ORDERING_TIME);
        insertCandidate(fixture, ORDERING_TIME, TimeOfDayPreference.ANY, null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<RecoveryWorkerOutcome> firstAttempt = executor.submit(
                    () -> attemptRecoveryAfterSignal(ready, start));
            Future<RecoveryWorkerOutcome> secondAttempt = executor.submit(
                    () -> attemptRecoveryAfterSignal(ready, start));

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Worker threads did not become ready");
            start.countDown();

            List<RecoveryWorkerOutcome> outcomes = List.of(
                    firstAttempt.get(20, TimeUnit.SECONDS),
                    secondAttempt.get(20, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream()
                    .filter(RecoveryWorkerOutcome.OFFER_CREATED::equals)
                    .count());
            assertEquals(1, outcomes.stream()
                    .filter(RecoveryWorkerOutcome.OFFER_ALREADY_EXISTS_FOR_JOB::equals)
                    .count());
            outcomes.forEach(outcome -> assertTrue(
                    outcome == RecoveryWorkerOutcome.OFFER_CREATED
                            || outcome == RecoveryWorkerOutcome.OFFER_ALREADY_EXISTS_FOR_JOB,
                    () -> "Unexpected concurrent worker outcome: " + outcome));
            assertEquals(1, slotOfferRepository.count());
        } finally {
            executor.shutdownNow();
        }
    }

    private RecoveryWorkerOutcome attemptRecoveryAfterSignal(
            CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Concurrent worker start signal timed out");
        }
        return recoveryWorkerService.attemptRecovery();
    }

    private JobFixture createJob(Instant startAt, Instant createdAt) {
        long number = SEQUENCE.incrementAndGet();
        Long specialtyId = insertSpecialty("Recovery Worker Specialty " + number);
        Long appointmentTypeId = insertAppointmentType(
                specialtyId, "Recovery Worker Type " + number);
        Long providerId = insertProvider(specialtyId, "Released Provider " + number);
        Long patientId = insertUser("released-patient-" + number, "PATIENT");
        Instant endAt = startAt.plusSeconds(3600);
        Long sourceAppointmentId = insertAppointment(
                patientId,
                providerId,
                appointmentTypeId,
                startAt,
                endAt,
                AppointmentStatus.CANCELLED);
        Long recoveryJobId = jdbcTemplate.queryForObject(
                "INSERT INTO recovery_job "
                        + "(source_appointment_id, status, created_at, updated_at) "
                        + "VALUES (?, 'OPEN', ?, ?) RETURNING id",
                Long.class,
                sourceAppointmentId,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        return new JobFixture(
                recoveryJobId,
                specialtyId,
                appointmentTypeId,
                providerId,
                startAt,
                endAt);
    }

    private Candidate insertCandidate(
            JobFixture fixture,
            Instant createdAt,
            TimeOfDayPreference timePreference,
            Long preferredProviderId) {
        long number = SEQUENCE.incrementAndGet();
        Long patientId = insertUser("worker-candidate-" + number, "PATIENT");
        Long anchorProviderId = insertProvider(
                fixture.specialtyId(), "Anchor Provider " + number);
        Instant anchorStart = fixture.endAt().plusSeconds(86_400);
        Long anchorAppointmentId = insertAppointment(
                patientId,
                anchorProviderId,
                fixture.appointmentTypeId(),
                anchorStart,
                anchorStart.plusSeconds(3600),
                AppointmentStatus.SCHEDULED);
        LocalDate localDate = fixture.startAt().atZone(CLINIC_TIME_ZONE).toLocalDate();
        Long waitlistEntryId = jdbcTemplate.queryForObject(
                "INSERT INTO waitlist_entry "
                        + "(patient_id, current_appointment_id, appointment_type_id, "
                        + "preferred_provider_id, earliest_appointment_date, "
                        + "latest_appointment_date, preferred_time_of_day, status, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                patientId,
                anchorAppointmentId,
                fixture.appointmentTypeId(),
                preferredProviderId,
                Date.valueOf(localDate.minusDays(7)),
                Date.valueOf(localDate.plusDays(7)),
                timePreference.name(),
                WaitlistEntryStatus.ACTIVE.name(),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        return new Candidate(waitlistEntryId);
    }

    private Long insertSpecialty(String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO specialty (name) VALUES (?) RETURNING id", Long.class, name);
    }

    private Long insertAppointmentType(Long specialtyId, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO appointment_type "
                        + "(name, duration_minutes, specialty_id) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                name,
                60,
                specialtyId);
    }

    private Long insertProvider(Long specialtyId, String name) {
        long number = SEQUENCE.incrementAndGet();
        Long providerUserId = insertUser("recovery-worker-provider-" + number, "PROVIDER");
        return jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "RECOVERY-WORKER-" + name + "-" + number);
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
                ? "PATIENT_CANCELLED"
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

    private void insertProviderBlock(
            JobFixture fixture, ProviderUnavailabilityStatus status) {
        jdbcTemplate.update(
                "INSERT INTO provider_unavailability "
                        + "(provider_id, start_at, end_at, status) VALUES (?, ?, ?, ?)",
                fixture.providerId(),
                Timestamp.from(fixture.startAt().plusSeconds(300)),
                Timestamp.from(fixture.endAt().minusSeconds(300)),
                status.name());
    }

    private Long insertSlotOffer(
            Long recoveryJobId, Long waitlistEntryId, SlotOfferStatus status) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO slot_offer "
                        + "(recovery_job_id, waitlist_entry_id, status, expires_at) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                recoveryJobId,
                waitlistEntryId,
                status.name(),
                Timestamp.from(Instant.now().plusSeconds(3600)));
    }

    private Instant futureStart() {
        return Instant.now().plusSeconds(14_400);
    }

    private RecoveryJob findJob(Long recoveryJobId) {
        return recoveryJobRepository.findById(recoveryJobId).orElseThrow();
    }

    private List<AuditLog> jobAudits(Long recoveryJobId) {
        return auditLogRepository.findAll().stream()
                .filter(audit -> "RecoveryJob".equals(audit.getEntityType()))
                .filter(audit -> recoveryJobId.equals(audit.getEntityId()))
                .toList();
    }

    private List<AuditLog> offerAudits(Long slotOfferId) {
        return auditLogRepository.findAll().stream()
                .filter(audit -> "SlotOffer".equals(audit.getEntityType()))
                .filter(audit -> slotOfferId.equals(audit.getEntityId()))
                .toList();
    }

    private void assertSystemAudit(
            AuditLog audit, String entityType, Long entityId, String action) {
        assertEquals(entityType, audit.getEntityType());
        assertEquals(entityId, audit.getEntityId());
        assertEquals(action, audit.getAction());
        assertEquals(ActorType.SYSTEM, audit.getActorType());
        assertNull(audit.getActorUserId());
        assertNull(audit.getReason());
    }

    private long workflowRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT "
                        + "(SELECT COUNT(*) FROM users) + "
                        + "(SELECT COUNT(*) FROM specialty) + "
                        + "(SELECT COUNT(*) FROM appointment_type) + "
                        + "(SELECT COUNT(*) FROM provider) + "
                        + "(SELECT COUNT(*) FROM appointment) + "
                        + "(SELECT COUNT(*) FROM recovery_job) + "
                        + "(SELECT COUNT(*) FROM waitlist_entry) + "
                        + "(SELECT COUNT(*) FROM slot_offer) + "
                        + "(SELECT COUNT(*) FROM audit_log)",
                Long.class);
    }

    private record JobFixture(
            Long recoveryJobId,
            Long specialtyId,
            Long appointmentTypeId,
            Long providerId,
            Instant startAt,
            Instant endAt) {
    }

    private record Candidate(Long waitlistEntryId) {
    }
}

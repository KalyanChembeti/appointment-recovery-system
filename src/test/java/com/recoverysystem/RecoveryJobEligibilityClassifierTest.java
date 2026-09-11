package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.service.RecoveryJobEligibilityClassifier;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@Transactional
class RecoveryJobEligibilityClassifierTest {

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
    private RecoveryJobEligibilityClassifier classifier;

    @Autowired
    private RecoveryJobRepository recoveryJobRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void occupiedIntervalFillsJobAndWritesAudit() {
        JobFixture fixture = createFixture(Instant.now().plusSeconds(14_400));
        insertScheduledAppointment(fixture);
        RecoveryJob lockedJob = lockJob(fixture.recoveryJobId());

        boolean shouldContinue = classifier.classifyAndHandle(
                lockedJob,
                fixture.providerId(),
                fixture.startAt(),
                fixture.endAt(),
                ActorType.USER,
                fixture.patientId());

        flushAndClear();
        RecoveryJob savedJob = findJob(fixture.recoveryJobId());
        assertFalse(shouldContinue);
        assertEquals(RecoveryJobStatus.FILLED, savedJob.getStatus());
        assertNotNull(savedJob.getFilledAt());
        assertAudit(
                fixture.recoveryJobId(),
                "FILLED",
                "INTERVAL_ALREADY_OCCUPIED",
                ActorType.USER,
                fixture.patientId());
    }

    @Test
    void activeBlockSuppressesJob() {
        JobFixture fixture = createFixture(Instant.now().plusSeconds(14_400));
        insertBlock(fixture, ProviderUnavailabilityStatus.ACTIVE);
        RecoveryJob lockedJob = lockJob(fixture.recoveryJobId());

        boolean shouldContinue = classifier.classifyAndHandle(
                lockedJob,
                fixture.providerId(),
                fixture.startAt(),
                fixture.endAt(),
                ActorType.SYSTEM,
                null);

        flushAndClear();
        RecoveryJob savedJob = findJob(fixture.recoveryJobId());
        assertFalse(shouldContinue);
        assertEquals(RecoveryJobStatus.SUPPRESSED, savedJob.getStatus());
        assertEquals("PROVIDER_BLOCK_ACTIVATED", savedJob.getSuppressionReason());
        assertAudit(
                fixture.recoveryJobId(), "SUPPRESS", null, ActorType.SYSTEM, null);
    }

    @Test
    void pendingBlockLeavesJobCompletelyUnchanged() {
        JobFixture fixture = createFixture(Instant.now().plusSeconds(14_400));
        jdbcTemplate.update(
                "UPDATE recovery_job SET suppression_reason = ? WHERE id = ?",
                "keep this value",
                fixture.recoveryJobId());
        insertBlock(fixture, ProviderUnavailabilityStatus.PENDING);
        RecoveryJob lockedJob = lockJob(fixture.recoveryJobId());
        Instant originalUpdatedAt = lockedJob.getUpdatedAt();

        boolean shouldContinue = classifier.classifyAndHandle(
                lockedJob,
                fixture.providerId(),
                fixture.startAt(),
                fixture.endAt(),
                ActorType.SYSTEM,
                null);

        flushAndClear();
        RecoveryJob savedJob = findJob(fixture.recoveryJobId());
        assertFalse(shouldContinue);
        assertEquals(RecoveryJobStatus.OPEN, savedJob.getStatus());
        assertEquals("keep this value", savedJob.getSuppressionReason());
        assertNull(savedJob.getFilledAt());
        assertEquals(originalUpdatedAt, savedJob.getUpdatedAt());
        assertTrue(jobAudits(fixture.recoveryJobId()).isEmpty());
    }

    @Test
    void closedLeadTimeSuppressesJob() {
        JobFixture fixture = createFixture(Instant.now().plusSeconds(60));
        RecoveryJob lockedJob = lockJob(fixture.recoveryJobId());

        boolean shouldContinue = classifier.classifyAndHandle(
                lockedJob,
                fixture.providerId(),
                fixture.startAt(),
                fixture.endAt(),
                ActorType.USER,
                fixture.patientId());

        flushAndClear();
        RecoveryJob savedJob = findJob(fixture.recoveryJobId());
        assertFalse(shouldContinue);
        assertEquals(RecoveryJobStatus.SUPPRESSED, savedJob.getStatus());
        assertEquals("LEAD_TIME_CLOSED", savedJob.getSuppressionReason());
        assertAudit(
                fixture.recoveryJobId(),
                "SUPPRESS",
                null,
                ActorType.USER,
                fixture.patientId());
    }

    @Test
    void eligibleJobContinuesWithoutChangesOrAudit() throws NoSuchMethodException {
        JobFixture fixture = createFixture(Instant.now().plusSeconds(14_400));
        RecoveryJob lockedJob = lockJob(fixture.recoveryJobId());
        Instant originalUpdatedAt = lockedJob.getUpdatedAt();

        boolean shouldContinue = classifier.classifyAndHandle(
                lockedJob,
                fixture.providerId(),
                fixture.startAt(),
                fixture.endAt(),
                ActorType.SYSTEM,
                null);

        flushAndClear();
        RecoveryJob savedJob = findJob(fixture.recoveryJobId());
        assertTrue(shouldContinue);
        assertEquals(RecoveryJobStatus.OPEN, savedJob.getStatus());
        assertEquals(originalUpdatedAt, savedJob.getUpdatedAt());
        assertTrue(jobAudits(fixture.recoveryJobId()).isEmpty());
        assertNull(RecoveryJobEligibilityClassifier.class.getAnnotation(Transactional.class));
        assertNull(RecoveryJobEligibilityClassifier.class
                .getDeclaredMethod(
                        "classifyAndHandle",
                        RecoveryJob.class,
                        Long.class,
                        Instant.class,
                        Instant.class,
                        ActorType.class,
                        Long.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void cancelledBlockDoesNotStopEligibleJob() {
        JobFixture fixture = createFixture(Instant.now().plusSeconds(14_400));
        insertBlock(fixture, ProviderUnavailabilityStatus.CANCELLED);
        RecoveryJob lockedJob = lockJob(fixture.recoveryJobId());
        Instant originalUpdatedAt = lockedJob.getUpdatedAt();

        boolean shouldContinue = classifier.classifyAndHandle(
                lockedJob,
                fixture.providerId(),
                fixture.startAt(),
                fixture.endAt(),
                ActorType.SYSTEM,
                null);

        flushAndClear();
        RecoveryJob savedJob = findJob(fixture.recoveryJobId());
        assertTrue(shouldContinue);
        assertEquals(RecoveryJobStatus.OPEN, savedJob.getStatus());
        assertEquals(originalUpdatedAt, savedJob.getUpdatedAt());
        assertTrue(jobAudits(fixture.recoveryJobId()).isEmpty());
    }

    @Test
    void occupiedIntervalWinsWhenActiveBlockAlsoExists() {
        JobFixture fixture = createFixture(Instant.now().plusSeconds(14_400));
        insertScheduledAppointment(fixture);
        insertBlock(fixture, ProviderUnavailabilityStatus.ACTIVE);
        RecoveryJob lockedJob = lockJob(fixture.recoveryJobId());

        boolean shouldContinue = classifier.classifyAndHandle(
                lockedJob,
                fixture.providerId(),
                fixture.startAt(),
                fixture.endAt(),
                ActorType.SYSTEM,
                null);

        flushAndClear();
        RecoveryJob savedJob = findJob(fixture.recoveryJobId());
        assertFalse(shouldContinue);
        assertEquals(RecoveryJobStatus.FILLED, savedJob.getStatus());
        assertNotNull(savedJob.getFilledAt());
        assertNull(savedJob.getSuppressionReason());
        assertAudit(
                fixture.recoveryJobId(),
                "FILLED",
                "INTERVAL_ALREADY_OCCUPIED",
                ActorType.SYSTEM,
                null);
    }

    private JobFixture createFixture(Instant startAt) {
        long number = SEQUENCE.incrementAndGet();
        Long specialtyId = jdbcTemplate.queryForObject(
                "INSERT INTO specialty (name) VALUES (?) RETURNING id",
                Long.class,
                "Eligibility Specialty " + number);
        Long appointmentTypeId = jdbcTemplate.queryForObject(
                "INSERT INTO appointment_type "
                        + "(name, duration_minutes, specialty_id) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "Eligibility Type " + number,
                60,
                specialtyId);
        Long providerUserId = insertUser("eligibility-provider-" + number, "PROVIDER");
        Long patientId = insertUser("eligibility-patient-" + number, "PATIENT");
        Long providerId = jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "ELIGIBILITY-" + number);
        Instant endAt = startAt.plusSeconds(3600);
        Long sourceAppointmentId = jdbcTemplate.queryForObject(
                "INSERT INTO appointment "
                        + "(patient_id, provider_id, appointment_type_id, start_at, end_at, "
                        + "status, cancellation_reason) "
                        + "VALUES (?, ?, ?, ?, ?, 'CANCELLED', 'PATIENT_CANCELLED') RETURNING id",
                Long.class,
                patientId,
                providerId,
                appointmentTypeId,
                Timestamp.from(startAt),
                Timestamp.from(endAt));
        Long recoveryJobId = jdbcTemplate.queryForObject(
                "INSERT INTO recovery_job (source_appointment_id, status) "
                        + "VALUES (?, 'OPEN') RETURNING id",
                Long.class,
                sourceAppointmentId);
        return new JobFixture(
                patientId,
                providerId,
                appointmentTypeId,
                recoveryJobId,
                startAt,
                endAt);
    }

    private Long insertUser(String emailPrefix, String role) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, role) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                emailPrefix + "@example.com",
                "test-password-hash",
                role);
    }

    private void insertScheduledAppointment(JobFixture fixture) {
        jdbcTemplate.update(
                "INSERT INTO appointment "
                        + "(patient_id, provider_id, appointment_type_id, start_at, end_at, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'SCHEDULED')",
                fixture.patientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                Timestamp.from(fixture.startAt().plusSeconds(300)),
                Timestamp.from(fixture.endAt().minusSeconds(300)));
    }

    private void insertBlock(JobFixture fixture, ProviderUnavailabilityStatus status) {
        jdbcTemplate.update(
                "INSERT INTO provider_unavailability "
                        + "(provider_id, start_at, end_at, status) VALUES (?, ?, ?, ?)",
                fixture.providerId(),
                Timestamp.from(fixture.startAt().plusSeconds(300)),
                Timestamp.from(fixture.endAt().minusSeconds(300)),
                status.name());
    }

    private RecoveryJob lockJob(Long recoveryJobId) {
        return recoveryJobRepository.findByIdForUpdate(recoveryJobId).orElseThrow();
    }

    private RecoveryJob findJob(Long recoveryJobId) {
        return recoveryJobRepository.findById(recoveryJobId).orElseThrow();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private List<AuditLog> jobAudits(Long recoveryJobId) {
        return auditLogRepository.findAll().stream()
                .filter(audit -> "RecoveryJob".equals(audit.getEntityType()))
                .filter(audit -> recoveryJobId.equals(audit.getEntityId()))
                .toList();
    }

    private void assertAudit(
            Long recoveryJobId,
            String action,
            String reason,
            ActorType actorType,
            Long actorUserId) {
        List<AuditLog> audits = jobAudits(recoveryJobId);
        assertEquals(1, audits.size());
        AuditLog auditLog = audits.getFirst();
        assertEquals(action, auditLog.getAction());
        assertEquals(reason, auditLog.getReason());
        assertEquals(actorType, auditLog.getActorType());
        assertEquals(actorUserId, auditLog.getActorUserId());
    }

    private record JobFixture(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Long recoveryJobId,
            Instant startAt,
            Instant endAt) {
    }
}

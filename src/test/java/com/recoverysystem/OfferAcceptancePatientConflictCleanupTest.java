package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.service.OfferAcceptancePatientConflictCleanup;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
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
class OfferAcceptancePatientConflictCleanupTest {

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
    private OfferAcceptancePatientConflictCleanup conflictCleanup;

    @Autowired
    private RecoveryJobRepository recoveryJobRepository;

    @Autowired
    private SlotOfferRepository slotOfferRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void cancelsOfferedSlotAndKeepsOpenRecoveryJobUnchanged() {
        ConflictFixture fixture = createFixture(
                RecoveryJobStatus.OPEN, SlotOfferStatus.OFFERED);
        RecoveryJob jobBefore = recoveryJobRepository
                .findById(fixture.recoveryJobId())
                .orElseThrow();

        conflictCleanup.cleanupAfterPatientConflict(
                fixture.recoveryJobId(),
                fixture.slotOfferId(),
                ActorType.USER,
                fixture.patientId());

        SlotOffer savedOffer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        assertEquals(SlotOfferStatus.CANCELLED, savedOffer.getStatus());
        RecoveryJob savedJob = recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        assertEquals(RecoveryJobStatus.OPEN, savedJob.getStatus());
        assertEquals(jobBefore.getUpdatedAt(), savedJob.getUpdatedAt());
        assertAudit(fixture.slotOfferId(), ActorType.USER, fixture.patientId());
    }

    @Test
    void cancelsOfferedSlotAndKeepsSuppressedRecoveryJobUnchanged() {
        ConflictFixture fixture = createFixture(
                RecoveryJobStatus.SUPPRESSED, SlotOfferStatus.OFFERED);
        RecoveryJob jobBefore = recoveryJobRepository
                .findById(fixture.recoveryJobId())
                .orElseThrow();

        conflictCleanup.cleanupAfterPatientConflict(
                fixture.recoveryJobId(),
                fixture.slotOfferId(),
                ActorType.SYSTEM,
                null);

        SlotOffer savedOffer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        assertEquals(SlotOfferStatus.CANCELLED, savedOffer.getStatus());
        RecoveryJob savedJob = recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        assertEquals(RecoveryJobStatus.SUPPRESSED, savedJob.getStatus());
        assertEquals(jobBefore.getUpdatedAt(), savedJob.getUpdatedAt());
        assertAudit(fixture.slotOfferId(), ActorType.SYSTEM, null);
    }

    @Test
    void leavesDeclinedOfferUnchanged() {
        assertResolvedOfferIsUnchanged(SlotOfferStatus.DECLINED);
    }

    @Test
    void leavesCancelledOfferUnchanged() {
        assertResolvedOfferIsUnchanged(SlotOfferStatus.CANCELLED);
    }

    @Test
    void leavesExpiredOfferUnchanged() {
        assertResolvedOfferIsUnchanged(SlotOfferStatus.EXPIRED);
    }

    @Test
    void missingRecoveryJobDoesNothing() {
        ConflictFixture fixture = createFixture(
                RecoveryJobStatus.OPEN, SlotOfferStatus.OFFERED);
        RecoveryJob jobBefore = recoveryJobRepository
                .findById(fixture.recoveryJobId())
                .orElseThrow();
        SlotOffer offerBefore = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        long auditCount = auditLogRepository.count();

        assertDoesNotThrow(() -> conflictCleanup.cleanupAfterPatientConflict(
                Long.MAX_VALUE,
                fixture.slotOfferId(),
                ActorType.USER,
                fixture.patientId()));

        RecoveryJob savedJob = recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        SlotOffer savedOffer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        assertEquals(jobBefore.getStatus(), savedJob.getStatus());
        assertEquals(jobBefore.getUpdatedAt(), savedJob.getUpdatedAt());
        assertEquals(offerBefore.getStatus(), savedOffer.getStatus());
        assertEquals(offerBefore.getUpdatedAt(), savedOffer.getUpdatedAt());
        assertEquals(auditCount, auditLogRepository.count());
    }

    @Test
    void missingSlotOfferDoesNothing() {
        ConflictFixture fixture = createFixture(
                RecoveryJobStatus.OPEN, SlotOfferStatus.OFFERED);
        RecoveryJob jobBefore = recoveryJobRepository
                .findById(fixture.recoveryJobId())
                .orElseThrow();
        SlotOffer offerBefore = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        long auditCount = auditLogRepository.count();

        assertDoesNotThrow(() -> conflictCleanup.cleanupAfterPatientConflict(
                fixture.recoveryJobId(),
                Long.MAX_VALUE,
                ActorType.USER,
                fixture.patientId()));

        RecoveryJob savedJob = recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        SlotOffer savedOffer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        assertEquals(jobBefore.getStatus(), savedJob.getStatus());
        assertEquals(jobBefore.getUpdatedAt(), savedJob.getUpdatedAt());
        assertEquals(offerBefore.getStatus(), savedOffer.getStatus());
        assertEquals(offerBefore.getUpdatedAt(), savedOffer.getUpdatedAt());
        assertEquals(auditCount, auditLogRepository.count());
    }

    @Test
    void cleanupServiceIsSpringProxied() {
        assertTrue(AopUtils.isAopProxy(conflictCleanup));
    }

    private void assertResolvedOfferIsUnchanged(SlotOfferStatus status) {
        ConflictFixture fixture = createFixture(RecoveryJobStatus.OPEN, status);
        SlotOffer offerBefore = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();

        conflictCleanup.cleanupAfterPatientConflict(
                fixture.recoveryJobId(),
                fixture.slotOfferId(),
                ActorType.USER,
                fixture.patientId());

        SlotOffer savedOffer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        assertEquals(status, savedOffer.getStatus());
        assertEquals(offerBefore.getUpdatedAt(), savedOffer.getUpdatedAt());
        assertTrue(offerAudits(fixture.slotOfferId()).isEmpty());
    }

    private void assertAudit(Long slotOfferId, ActorType actorType, Long actorUserId) {
        List<AuditLog> audits = offerAudits(slotOfferId);
        assertEquals(1, audits.size());
        AuditLog audit = audits.getFirst();
        assertEquals("SlotOffer", audit.getEntityType());
        assertEquals(slotOfferId, audit.getEntityId());
        assertEquals("CANCEL", audit.getAction());
        assertEquals("PATIENT_SCHEDULE_CONFLICT", audit.getReason());
        assertEquals(actorType, audit.getActorType());
        if (actorUserId == null) {
            assertNull(audit.getActorUserId());
        } else {
            assertEquals(actorUserId, audit.getActorUserId());
        }
    }

    private ConflictFixture createFixture(
            RecoveryJobStatus recoveryJobStatus,
            SlotOfferStatus slotOfferStatus) {
        long number = SEQUENCE.incrementAndGet();
        Long specialtyId = jdbcTemplate.queryForObject(
                "INSERT INTO specialty (name) VALUES (?) RETURNING id",
                Long.class,
                "Patient Conflict Specialty " + number);
        Long appointmentTypeId = jdbcTemplate.queryForObject(
                "INSERT INTO appointment_type "
                        + "(name, duration_minutes, specialty_id) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "Patient Conflict Type " + number,
                60,
                specialtyId);
        Long providerUserId = insertUser("patient-conflict-provider-" + number, "PROVIDER");
        Long patientId = insertUser("patient-conflict-patient-" + number, "PATIENT");
        Long providerId = jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "PATIENT-CONFLICT-" + number);
        Instant startAt = Instant.parse("2080-01-01T14:00:00Z")
                .plusSeconds(number * 7200);
        Long appointmentId = jdbcTemplate.queryForObject(
                "INSERT INTO appointment "
                        + "(patient_id, provider_id, appointment_type_id, start_at, end_at, "
                        + "status, cancellation_reason) "
                        + "VALUES (?, ?, ?, ?, ?, 'CANCELLED', 'PATIENT_CANCELLED') RETURNING id",
                Long.class,
                patientId,
                providerId,
                appointmentTypeId,
                Timestamp.from(startAt),
                Timestamp.from(startAt.plusSeconds(3600)));
        LocalDate appointmentDate = LocalDate.of(2080, 1, 1).plusDays(number);
        Long waitlistEntryId = jdbcTemplate.queryForObject(
                "INSERT INTO waitlist_entry "
                        + "(patient_id, current_appointment_id, appointment_type_id, "
                        + "earliest_appointment_date, latest_appointment_date) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                patientId,
                appointmentId,
                appointmentTypeId,
                Date.valueOf(appointmentDate),
                Date.valueOf(appointmentDate.plusDays(7)));
        Long recoveryJobId = jdbcTemplate.queryForObject(
                "INSERT INTO recovery_job (source_appointment_id, status) "
                        + "VALUES (?, ?) RETURNING id",
                Long.class,
                appointmentId,
                recoveryJobStatus.name());
        Long slotOfferId = jdbcTemplate.queryForObject(
                "INSERT INTO slot_offer "
                        + "(recovery_job_id, waitlist_entry_id, status, expires_at) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                recoveryJobId,
                waitlistEntryId,
                slotOfferStatus.name(),
                Timestamp.from(Instant.parse("2090-01-01T00:00:00Z")));

        return new ConflictFixture(patientId, recoveryJobId, slotOfferId);
    }

    private Long insertUser(String emailPrefix, String role) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, role) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                emailPrefix + "@example.com",
                "test-password-hash",
                role);
    }

    private List<AuditLog> offerAudits(Long slotOfferId) {
        return auditLogRepository.findAll().stream()
                .filter(log -> "SlotOffer".equals(log.getEntityType()))
                .filter(log -> slotOfferId.equals(log.getEntityId()))
                .toList();
    }

    private record ConflictFixture(Long patientId, Long recoveryJobId, Long slotOfferId) {
    }
}

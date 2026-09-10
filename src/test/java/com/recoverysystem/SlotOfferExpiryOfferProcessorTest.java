package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.service.SlotOfferAggressiveExpiryTransition;
import com.recoverysystem.service.SlotOfferExpiryOfferProcessor;
import com.recoverysystem.service.SlotOfferExpiryWorkerService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class SlotOfferExpiryOfferProcessorTest {

    private static final AtomicLong UNIQUE_SEQUENCE = new AtomicLong();

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
    private SlotOfferExpiryOfferProcessor slotOfferExpiryOfferProcessor;

    @Autowired
    private SlotOfferRepository slotOfferRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void staleOfferedSlotExpiresWithOneSystemAudit() {
        OfferFixture fixture = createOffer(
                SlotOfferStatus.OFFERED, Instant.now().minusSeconds(60));

        boolean expired =
                slotOfferExpiryOfferProcessor.expireOfferIfEligible(fixture.slotOfferId());

        assertTrue(expired);
        assertEquals(
                SlotOfferStatus.EXPIRED,
                slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow().getStatus());
        List<AuditLog> audits = offerAudits(fixture.slotOfferId());
        assertEquals(1, audits.size());
        AuditLog audit = audits.getFirst();
        assertEquals("SlotOffer", audit.getEntityType());
        assertEquals(fixture.slotOfferId(), audit.getEntityId());
        assertEquals("EXPIRE", audit.getAction());
        assertEquals(ActorType.SYSTEM, audit.getActorType());
        assertNull(audit.getActorUserId());
        assertNull(audit.getReason());
    }

    @Test
    void futureOfferedSlotIsUntouchedWithoutAuditOrException() {
        assertIneligibleOfferIsSilentlySkipped(
                SlotOfferStatus.OFFERED, Instant.now().plusSeconds(3600));
    }

    @Test
    void acceptedOfferIsSilentlySkippedUnlikeWorkflow5Rejection() {
        assertIneligibleOfferIsSilentlySkipped(
                SlotOfferStatus.ACCEPTED, Instant.now().minusSeconds(60));
    }

    @Test
    void declinedOfferIsSilentlySkippedWithoutAuditOrException() {
        assertIneligibleOfferIsSilentlySkipped(
                SlotOfferStatus.DECLINED, Instant.now().minusSeconds(60));
    }

    @Test
    void doubleExpiryIsAnIdempotentSkipWithoutDuplicateAudit() {
        OfferFixture fixture = createOffer(
                SlotOfferStatus.OFFERED, Instant.now().minusSeconds(60));
        assertTrue(slotOfferExpiryOfferProcessor.expireOfferIfEligible(fixture.slotOfferId()));
        SlotOffer afterFirstExpiry =
                slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        Instant updatedAt = afterFirstExpiry.getUpdatedAt();

        boolean expiredAgain =
                slotOfferExpiryOfferProcessor.expireOfferIfEligible(fixture.slotOfferId());

        assertFalse(expiredAgain);
        SlotOffer reloaded = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        assertEquals(SlotOfferStatus.EXPIRED, reloaded.getStatus());
        assertEquals(updatedAt, reloaded.getUpdatedAt());
        assertEquals(1, offerAudits(fixture.slotOfferId()).size());
    }

    @Test
    void cancelledOfferIsSilentlySkippedWithoutAuditOrException() {
        assertIneligibleOfferIsSilentlySkipped(
                SlotOfferStatus.CANCELLED, Instant.now().minusSeconds(60));
    }

    @Test
    void nonexistentSlotOfferReturnsFalseWithoutExceptionOrAudit() {
        long auditCount = auditLogRepository.count();

        boolean expired = slotOfferExpiryOfferProcessor.expireOfferIfEligible(Long.MAX_VALUE);

        assertFalse(expired);
        assertEquals(auditCount, auditLogRepository.count());
    }

    @Test
    void expiryComponentsHaveRequiredTransactionsAndNoRecoveryJobReferences()
            throws ReflectiveOperationException, IOException {
        assertNull(SlotOfferAggressiveExpiryTransition.class.getAnnotation(Transactional.class));
        assertNull(SlotOfferAggressiveExpiryTransition.class
                .getDeclaredMethod("expireIfStaleOffered", SlotOffer.class)
                .getAnnotation(Transactional.class));
        assertNull(SlotOfferExpiryWorkerService.class.getAnnotation(Transactional.class));
        assertNull(SlotOfferExpiryWorkerService.class
                .getDeclaredMethod("expireStaleOffers", int.class)
                .getAnnotation(Transactional.class));

        Transactional processorTransaction = SlotOfferExpiryOfferProcessor.class
                .getDeclaredMethod("expireOfferIfEligible", Long.class)
                .getAnnotation(Transactional.class);
        assertEquals(Isolation.READ_COMMITTED, processorTransaction.isolation());

        List<Path> sourceFiles = List.of(
                Path.of("src/main/java/com/recoverysystem/service/SlotOfferAggressiveExpiryTransition.java"),
                Path.of("src/main/java/com/recoverysystem/service/SlotOfferExpiryOfferProcessor.java"),
                Path.of("src/main/java/com/recoverysystem/service/SlotOfferExpiryWorkerService.java"));
        for (Path sourceFile : sourceFiles) {
            assertFalse(
                    Files.readString(sourceFile).contains("RecoveryJob"),
                    () -> sourceFile + " must not reference a RecoveryJob type or repository");
        }
    }

    private void assertIneligibleOfferIsSilentlySkipped(
            SlotOfferStatus status, Instant expiresAt) {
        OfferFixture fixture = createOffer(status, expiresAt);
        SlotOffer before = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        Instant updatedAt = before.getUpdatedAt();

        boolean expired =
                slotOfferExpiryOfferProcessor.expireOfferIfEligible(fixture.slotOfferId());

        assertFalse(expired);
        SlotOffer reloaded = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        assertEquals(status, reloaded.getStatus());
        assertEquals(updatedAt, reloaded.getUpdatedAt());
        assertTrue(offerAudits(fixture.slotOfferId()).isEmpty());
    }

    private OfferFixture createOffer(SlotOfferStatus status, Instant expiresAt) {
        long sequence = UNIQUE_SEQUENCE.incrementAndGet();
        Long specialtyId = jdbcTemplate.queryForObject(
                "INSERT INTO specialty (name) VALUES (?) RETURNING id",
                Long.class,
                "Expiry Processor Specialty " + sequence);
        Long appointmentTypeId = jdbcTemplate.queryForObject(
                "INSERT INTO appointment_type "
                        + "(name, duration_minutes, specialty_id) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "Expiry Processor Type " + sequence,
                60,
                specialtyId);
        Long providerUserId = insertUser("expiry-provider-" + sequence, "PROVIDER");
        Long patientId = insertUser("expiry-patient-" + sequence, "PATIENT");
        Long providerId = jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "EXPIRY-PROCESSOR-" + sequence);
        Instant appointmentStart = Instant.parse("2100-01-01T14:00:00Z")
                .plusSeconds(sequence * 7200);
        Long appointmentId = jdbcTemplate.queryForObject(
                "INSERT INTO appointment "
                        + "(patient_id, provider_id, appointment_type_id, start_at, end_at, "
                        + "status, cancellation_reason) "
                        + "VALUES (?, ?, ?, ?, ?, 'CANCELLED', 'PATIENT_CANCELLED') RETURNING id",
                Long.class,
                patientId,
                providerId,
                appointmentTypeId,
                Timestamp.from(appointmentStart),
                Timestamp.from(appointmentStart.plusSeconds(3600)));
        LocalDate appointmentDate = LocalDate.of(2100, 1, 1).plusDays(sequence);
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
                        + "VALUES (?, 'OPEN') RETURNING id",
                Long.class,
                appointmentId);
        Long slotOfferId = jdbcTemplate.queryForObject(
                "INSERT INTO slot_offer "
                        + "(recovery_job_id, waitlist_entry_id, status, expires_at) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                recoveryJobId,
                waitlistEntryId,
                status.name(),
                Timestamp.from(expiresAt));
        return new OfferFixture(slotOfferId);
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

    private record OfferFixture(Long slotOfferId) {
    }
}

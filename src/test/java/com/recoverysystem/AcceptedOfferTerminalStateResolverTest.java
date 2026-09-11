package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.exception.OfferAlreadyAcceptedException;
import com.recoverysystem.exception.OfferAlreadyResolvedException;
import com.recoverysystem.exception.OfferExpiredException;
import com.recoverysystem.exception.SlotOfferNotFoundException;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.service.AcceptedOfferTerminalStateResolver;
import com.recoverysystem.support.DefaultRollbackExpiryHarness;
import com.recoverysystem.support.NoRollbackExpiryHarness;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class AcceptedOfferTerminalStateResolverTest {

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
    private AcceptedOfferTerminalStateResolver acceptedOfferTerminalStateResolver;

    @Autowired
    private NoRollbackExpiryHarness noRollbackExpiryHarness;

    @Autowired
    private DefaultRollbackExpiryHarness defaultRollbackExpiryHarness;

    @Autowired
    private SlotOfferRepository slotOfferRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void offeredAndUnexpiredReturnsNormallyWithoutChangesOrAudit()
            throws NoSuchMethodException {
        OfferFixture fixture = createOffer(
                SlotOfferStatus.OFFERED, Instant.now().plusSeconds(3600));
        SlotOffer offer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        Instant updatedAt = offer.getUpdatedAt();

        assertDoesNotThrow(() -> acceptedOfferTerminalStateResolver.resolve(offer));

        assertEquals(SlotOfferStatus.OFFERED, offer.getStatus());
        assertPersistedStatusAndUpdatedAt(fixture.slotOfferId(), SlotOfferStatus.OFFERED, updatedAt);
        assertTrue(offerAudits(fixture.slotOfferId()).isEmpty());
        assertNull(AcceptedOfferTerminalStateResolver.class.getAnnotation(Transactional.class));
        assertNull(AcceptedOfferTerminalStateResolver.class
                .getDeclaredMethod("resolve", SlotOffer.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void acceptedThrowsAlreadyAcceptedWithoutChangesOrAudit() {
        OfferFixture fixture = createOffer(
                SlotOfferStatus.ACCEPTED, Instant.now().plusSeconds(3600));
        SlotOffer offer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        Instant updatedAt = offer.getUpdatedAt();

        OfferAlreadyAcceptedException exception = assertThrows(
                OfferAlreadyAcceptedException.class,
                () -> acceptedOfferTerminalStateResolver.resolve(offer));

        assertTrue(exception.getMessage().contains(fixture.slotOfferId().toString()));
        assertEquals(SlotOfferStatus.ACCEPTED, offer.getStatus());
        assertPersistedStatusAndUpdatedAt(
                fixture.slotOfferId(), SlotOfferStatus.ACCEPTED, updatedAt);
        assertTrue(offerAudits(fixture.slotOfferId()).isEmpty());
    }

    @Test
    void declinedThrowsAlreadyResolvedAndReportsActualStatus() {
        assertResolvedOfferIsRejectedWithoutChanges(SlotOfferStatus.DECLINED);
    }

    @Test
    void cancelledThrowsAlreadyResolvedAndReportsActualStatus() {
        assertResolvedOfferIsRejectedWithoutChanges(SlotOfferStatus.CANCELLED);
    }

    @Test
    void alreadyExpiredThrowsExpiredWithoutChangesOrAudit() {
        OfferFixture fixture = createOffer(
                SlotOfferStatus.EXPIRED, Instant.now().minusSeconds(60));
        SlotOffer offer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        Instant updatedAt = offer.getUpdatedAt();

        OfferExpiredException exception = assertThrows(
                OfferExpiredException.class,
                () -> acceptedOfferTerminalStateResolver.resolve(offer));

        assertTrue(exception.getMessage().contains(fixture.slotOfferId().toString()));
        assertEquals(SlotOfferStatus.EXPIRED, offer.getStatus());
        assertPersistedStatusAndUpdatedAt(
                fixture.slotOfferId(), SlotOfferStatus.EXPIRED, updatedAt);
        assertTrue(offerAudits(fixture.slotOfferId()).isEmpty());
    }

    @Test
    void staleOfferedAggressivelyExpiresAndAuditsBeforeThrowing() {
        OfferFixture fixture = createOffer(
                SlotOfferStatus.OFFERED, Instant.now().minusSeconds(60));
        SlotOffer offer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();

        assertThrows(
                OfferExpiredException.class,
                () -> acceptedOfferTerminalStateResolver.resolve(offer));

        assertEquals(SlotOfferStatus.EXPIRED, offer.getStatus());
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
    void noRollbackHarnessCommitsAggressiveExpiryDespiteException() {
        assertNull(AcceptedOfferTerminalStateResolverTest.class
                .getAnnotation(Transactional.class));
        assertTrue(AopUtils.isAopProxy(noRollbackExpiryHarness));
        OfferFixture fixture = createOffer(
                SlotOfferStatus.OFFERED, Instant.now().minusSeconds(60));

        assertThrows(
                OfferExpiredException.class,
                () -> noRollbackExpiryHarness.lockAndResolve(fixture.slotOfferId()));

        SlotOffer persisted = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        assertEquals(SlotOfferStatus.EXPIRED, persisted.getStatus());
        List<AuditLog> audits = offerAudits(fixture.slotOfferId());
        assertEquals(1, audits.size());
        assertEquals("EXPIRE", audits.getFirst().getAction());
    }

    @Test
    void defaultRollbackHarnessRollsBackAggressiveExpiry() {
        assertTrue(AopUtils.isAopProxy(defaultRollbackExpiryHarness));
        OfferFixture fixture = createOffer(
                SlotOfferStatus.OFFERED, Instant.now().minusSeconds(60));

        assertThrows(
                OfferExpiredException.class,
                () -> defaultRollbackExpiryHarness.lockAndResolve(fixture.slotOfferId()));

        SlotOffer persisted = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        assertEquals(SlotOfferStatus.OFFERED, persisted.getStatus());
        assertTrue(offerAudits(fixture.slotOfferId()).isEmpty());
    }

    @Test
    void nonexistentOfferViaHarnessThrowsNotFound() {
        assertThrows(
                SlotOfferNotFoundException.class,
                () -> noRollbackExpiryHarness.lockAndResolve(Long.MAX_VALUE));
    }

    private void assertResolvedOfferIsRejectedWithoutChanges(SlotOfferStatus status) {
        OfferFixture fixture = createOffer(status, Instant.now().plusSeconds(3600));
        SlotOffer offer = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        Instant updatedAt = offer.getUpdatedAt();

        OfferAlreadyResolvedException exception = assertThrows(
                OfferAlreadyResolvedException.class,
                () -> acceptedOfferTerminalStateResolver.resolve(offer));

        assertTrue(exception.getMessage().contains(status.name()));
        assertEquals(status, offer.getStatus());
        assertPersistedStatusAndUpdatedAt(fixture.slotOfferId(), status, updatedAt);
        assertTrue(offerAudits(fixture.slotOfferId()).isEmpty());
    }

    private void assertPersistedStatusAndUpdatedAt(
            Long slotOfferId, SlotOfferStatus status, Instant updatedAt) {
        SlotOffer persisted = slotOfferRepository.findById(slotOfferId).orElseThrow();
        assertEquals(status, persisted.getStatus());
        assertEquals(updatedAt, persisted.getUpdatedAt());
    }

    private OfferFixture createOffer(SlotOfferStatus status, Instant expiresAt) {
        long sequence = UNIQUE_SEQUENCE.incrementAndGet();
        Long specialtyId = jdbcTemplate.queryForObject(
                "INSERT INTO specialty (name) VALUES (?) RETURNING id",
                Long.class,
                "Acceptance Resolver Specialty " + sequence);
        Long appointmentTypeId = jdbcTemplate.queryForObject(
                "INSERT INTO appointment_type "
                        + "(name, duration_minutes, specialty_id) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "Acceptance Resolver Type " + sequence,
                60,
                specialtyId);
        Long providerUserId = insertUser("acceptance-resolver-provider-" + sequence, "PROVIDER");
        Long patientId = insertUser("acceptance-resolver-patient-" + sequence, "PATIENT");
        Long providerId = jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "ACCEPTANCE-RESOLVER-" + sequence);
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

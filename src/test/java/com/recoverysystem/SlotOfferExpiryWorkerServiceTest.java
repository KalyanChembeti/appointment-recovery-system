package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.service.SlotOfferExpiryWorkerService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
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
class SlotOfferExpiryWorkerServiceTest {

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
    private SlotOfferExpiryWorkerService slotOfferExpiryWorkerService;

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
    void batchExpiresOnlyThreeEligibleOffersAndSkipsFutureAndAcceptedOffers() {
        Instant past = Instant.now().minusSeconds(600);
        List<OfferFixture> eligibleOffers = List.of(
                createOffer(SlotOfferStatus.OFFERED, past),
                createOffer(SlotOfferStatus.OFFERED, past),
                createOffer(SlotOfferStatus.OFFERED, past));
        OfferFixture futureOffer =
                createOffer(SlotOfferStatus.OFFERED, Instant.now().plusSeconds(3600));
        OfferFixture acceptedOffer = createOffer(SlotOfferStatus.ACCEPTED, past);
        Instant futureUpdatedAt = updatedAt(futureOffer.slotOfferId());
        Instant acceptedUpdatedAt = updatedAt(acceptedOffer.slotOfferId());

        int expiredCount = slotOfferExpiryWorkerService.expireStaleOffers(10);

        assertEquals(3, expiredCount);
        eligibleOffers.forEach(offer -> assertEquals(
                SlotOfferStatus.EXPIRED, statusOf(offer.slotOfferId())));
        assertEquals(SlotOfferStatus.OFFERED, statusOf(futureOffer.slotOfferId()));
        assertEquals(futureUpdatedAt, updatedAt(futureOffer.slotOfferId()));
        assertEquals(SlotOfferStatus.ACCEPTED, statusOf(acceptedOffer.slotOfferId()));
        assertEquals(acceptedUpdatedAt, updatedAt(acceptedOffer.slotOfferId()));
        assertEquals(3, expiryAudits().size());
    }

    @Test
    void batchSizeLimitsExpiryToExactlyTwoOfFiveEligibleOffers() {
        Instant past = Instant.now().minusSeconds(600);
        List<OfferFixture> offers = List.of(
                createOffer(SlotOfferStatus.OFFERED, past),
                createOffer(SlotOfferStatus.OFFERED, past),
                createOffer(SlotOfferStatus.OFFERED, past),
                createOffer(SlotOfferStatus.OFFERED, past),
                createOffer(SlotOfferStatus.OFFERED, past));
        Map<Long, Instant> originalUpdatedAt = offers.stream().collect(Collectors.toMap(
                OfferFixture::slotOfferId,
                offer -> updatedAt(offer.slotOfferId())));

        int expiredCount = slotOfferExpiryWorkerService.expireStaleOffers(2);

        assertEquals(2, expiredCount);
        assertEquals(
                2,
                offers.stream()
                        .filter(offer -> statusOf(offer.slotOfferId()) == SlotOfferStatus.EXPIRED)
                        .count());
        List<OfferFixture> untouchedOffers = offers.stream()
                .filter(offer -> statusOf(offer.slotOfferId()) == SlotOfferStatus.OFFERED)
                .toList();
        assertEquals(3, untouchedOffers.size());
        untouchedOffers.forEach(offer -> assertEquals(
                originalUpdatedAt.get(offer.slotOfferId()), updatedAt(offer.slotOfferId())));
        assertEquals(2, expiryAudits().size());
    }

    @Test
    void batchSelectsOldestEligibleOffersByCreatedAt() {
        Instant past = Instant.now().minusSeconds(600);
        OfferFixture newest = createOffer(SlotOfferStatus.OFFERED, past);
        OfferFixture oldest = createOffer(SlotOfferStatus.OFFERED, past);
        OfferFixture secondOldest = createOffer(SlotOfferStatus.OFFERED, past);
        OfferFixture secondNewest = createOffer(SlotOfferStatus.OFFERED, past);
        Instant referenceTime = Instant.parse("2050-01-01T00:00:00Z");
        setCreatedAt(newest.slotOfferId(), referenceTime.plusSeconds(400));
        setCreatedAt(oldest.slotOfferId(), referenceTime.plusSeconds(100));
        setCreatedAt(secondOldest.slotOfferId(), referenceTime.plusSeconds(200));
        setCreatedAt(secondNewest.slotOfferId(), referenceTime.plusSeconds(300));
        Instant newestUpdatedAt = updatedAt(newest.slotOfferId());
        Instant secondNewestUpdatedAt = updatedAt(secondNewest.slotOfferId());

        int expiredCount = slotOfferExpiryWorkerService.expireStaleOffers(2);

        assertEquals(2, expiredCount);
        assertEquals(SlotOfferStatus.EXPIRED, statusOf(oldest.slotOfferId()));
        assertEquals(SlotOfferStatus.EXPIRED, statusOf(secondOldest.slotOfferId()));
        assertEquals(SlotOfferStatus.OFFERED, statusOf(secondNewest.slotOfferId()));
        assertEquals(secondNewestUpdatedAt, updatedAt(secondNewest.slotOfferId()));
        assertEquals(SlotOfferStatus.OFFERED, statusOf(newest.slotOfferId()));
        assertEquals(newestUpdatedAt, updatedAt(newest.slotOfferId()));
        assertEquals(
                Set.of(oldest.slotOfferId(), secondOldest.slotOfferId()),
                expiryAudits().stream().map(AuditLog::getEntityId).collect(Collectors.toSet()));
    }

    private OfferFixture createOffer(SlotOfferStatus status, Instant expiresAt) {
        long sequence = UNIQUE_SEQUENCE.incrementAndGet();
        Long specialtyId = jdbcTemplate.queryForObject(
                "INSERT INTO specialty (name) VALUES (?) RETURNING id",
                Long.class,
                "Expiry Worker Specialty " + sequence);
        Long appointmentTypeId = jdbcTemplate.queryForObject(
                "INSERT INTO appointment_type "
                        + "(name, duration_minutes, specialty_id) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "Expiry Worker Type " + sequence,
                60,
                specialtyId);
        Long providerUserId = insertUser("worker-provider-" + sequence, "PROVIDER");
        Long patientId = insertUser("worker-patient-" + sequence, "PATIENT");
        Long providerId = jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "EXPIRY-WORKER-" + sequence);
        Instant appointmentStart = Instant.parse("2150-01-01T14:00:00Z")
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
        LocalDate appointmentDate = LocalDate.of(2150, 1, 1).plusDays(sequence);
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

    private void setCreatedAt(Long slotOfferId, Instant createdAt) {
        jdbcTemplate.update(
                "UPDATE slot_offer SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                slotOfferId);
    }

    private SlotOfferStatus statusOf(Long slotOfferId) {
        return slotOfferRepository.findById(slotOfferId).orElseThrow().getStatus();
    }

    private Instant updatedAt(Long slotOfferId) {
        return slotOfferRepository.findById(slotOfferId).orElseThrow().getUpdatedAt();
    }

    private List<AuditLog> expiryAudits() {
        return auditLogRepository.findAll().stream()
                .filter(log -> "SlotOffer".equals(log.getEntityType()))
                .filter(log -> "EXPIRE".equals(log.getAction()))
                .toList();
    }

    private record OfferFixture(Long slotOfferId) {
    }
}

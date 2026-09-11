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
import com.recoverysystem.service.SlotOfferCleanupDiscovery;
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
class SlotOfferCleanupDiscoveryTest {

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
    private SlotOfferCleanupDiscovery cleanupDiscovery;

    @Autowired
    private SlotOfferRepository slotOfferRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void findsSecondOfferedOfferForAcceptedEntry() {
        DiscoveryFixture fixture = createFixture(SlotOfferStatus.OFFERED);
        Long sourceAppointmentId = insertAppointment(
                fixture.patientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.newStart().minusSeconds(7200));
        Long secondOfferId = insertOffer(
                fixture.acceptedEntryId(), sourceAppointmentId, SlotOfferStatus.OFFERED);

        List<Long> result = discover(fixture, List.of());

        assertEquals(List.of(secondOfferId), result);
    }

    @Test
    void findsOfferedOfferForSiblingEntry() {
        DiscoveryFixture fixture = createFixture(SlotOfferStatus.OFFERED);
        Long siblingEntryId = insertWaitlistEntry(
                fixture.patientId(),
                fixture.oldAppointmentId(),
                fixture.appointmentTypeId(),
                "ACTIVE");
        Long sourceAppointmentId = insertAppointment(
                fixture.patientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.newStart().minusSeconds(7200));
        Long siblingOfferId = insertOffer(
                siblingEntryId, sourceAppointmentId, SlotOfferStatus.OFFERED);

        List<Long> result = discover(fixture, List.of(siblingEntryId));

        assertEquals(List.of(siblingOfferId), result);
    }

    @Test
    void findsOverlappingOfferForOtherActiveEntryOfSamePatient() {
        DiscoveryFixture fixture = createFixture(SlotOfferStatus.OFFERED);
        Long otherAppointmentId = insertAppointment(
                fixture.patientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.newStart().plusSeconds(86400));
        Long otherEntryId = insertWaitlistEntry(
                fixture.patientId(),
                otherAppointmentId,
                fixture.appointmentTypeId(),
                "ACTIVE");
        Long sourceAppointmentId = insertAppointment(
                fixture.patientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.newStart().plusSeconds(900));
        Long overlappingOfferId = insertOffer(
                otherEntryId, sourceAppointmentId, SlotOfferStatus.OFFERED);

        List<Long> result = discover(fixture, List.of());

        assertEquals(List.of(overlappingOfferId), result);
    }

    @Test
    void ignoresNonOverlappingOfferForOtherActiveEntry() {
        DiscoveryFixture fixture = createFixture(SlotOfferStatus.OFFERED);
        Long otherAppointmentId = insertAppointment(
                fixture.patientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.newStart().plusSeconds(86400));
        Long otherEntryId = insertWaitlistEntry(
                fixture.patientId(),
                otherAppointmentId,
                fixture.appointmentTypeId(),
                "ACTIVE");
        Long sourceAppointmentId = insertAppointment(
                fixture.patientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.newEnd());
        insertOffer(otherEntryId, sourceAppointmentId, SlotOfferStatus.OFFERED);

        List<Long> result = discover(fixture, List.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void ignoresOverlappingOfferForDifferentPatient() {
        DiscoveryFixture fixture = createFixture(SlotOfferStatus.OFFERED);
        Long otherPatientId = insertUser("cleanup-other-patient-" + SEQUENCE.incrementAndGet(),
                "PATIENT");
        Long otherAppointmentId = insertAppointment(
                otherPatientId,
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.newStart().plusSeconds(86400));
        Long otherEntryId = insertWaitlistEntry(
                otherPatientId,
                otherAppointmentId,
                fixture.appointmentTypeId(),
                "ACTIVE");
        Long sourceAppointmentId = insertAppointment(
                otherPatientId,
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.newStart().plusSeconds(900));
        insertOffer(otherEntryId, sourceAppointmentId, SlotOfferStatus.OFFERED);

        List<Long> result = discover(fixture, List.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void categoryDExcludesEntryAnchoredToOldAppointment() {
        DiscoveryFixture fixture = createFixture(SlotOfferStatus.OFFERED);
        Long sameAnchorEntryId = insertWaitlistEntry(
                fixture.patientId(),
                fixture.oldAppointmentId(),
                fixture.appointmentTypeId(),
                "ACTIVE");
        Long sourceAppointmentId = insertAppointment(
                fixture.patientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.newStart().plusSeconds(900));
        insertOffer(sameAnchorEntryId, sourceAppointmentId, SlotOfferStatus.OFFERED);

        List<Long> result = discover(fixture, List.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void combinesCleanupCategoriesWithoutDuplicatesAndSortsIds() {
        DiscoveryFixture fixture = createFixture(SlotOfferStatus.OFFERED);

        Long otherAppointmentId = insertAppointment(
                fixture.patientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.newStart().plusSeconds(86400));
        Long otherEntryId = insertWaitlistEntry(
                fixture.patientId(),
                otherAppointmentId,
                fixture.appointmentTypeId(),
                "ACTIVE");
        Long dSourceId = insertAppointment(
                fixture.patientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.newStart().plusSeconds(900));
        Long categoryDOfferId = insertOffer(
                otherEntryId, dSourceId, SlotOfferStatus.OFFERED);

        Long siblingEntryId = insertWaitlistEntry(
                fixture.patientId(),
                fixture.oldAppointmentId(),
                fixture.appointmentTypeId(),
                "ACTIVE");
        Long cSourceId = insertAppointment(
                fixture.patientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.newStart().minusSeconds(7200));
        Long categoryCOfferId = insertOffer(
                siblingEntryId, cSourceId, SlotOfferStatus.OFFERED);

        Long bSourceId = insertAppointment(
                fixture.patientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.newStart().minusSeconds(14400));
        Long categoryBOfferId = insertOffer(
                fixture.acceptedEntryId(), bSourceId, SlotOfferStatus.OFFERED);

        List<Long> result = discover(
                fixture, List.of(siblingEntryId, siblingEntryId));
        List<Long> expected = List.of(categoryBOfferId, categoryCOfferId, categoryDOfferId)
                .stream()
                .sorted()
                .toList();

        assertEquals(expected, result);
        assertEquals(3, result.stream().distinct().count());
        assertFalse(result.contains(fixture.acceptedOfferId()));
    }

    @Test
    void cleanupCancelsOfferedOfferAndWritesAudit() {
        DiscoveryFixture fixture = createFixture(SlotOfferStatus.OFFERED);
        SlotOffer offer = slotOfferRepository.findById(fixture.acceptedOfferId()).orElseThrow();

        cleanupDiscovery.cleanupIfStillOffered(
                offer,
                "SUPERSEDED_BY_ACCEPTANCE",
                ActorType.USER,
                fixture.patientId());

        assertEquals(SlotOfferStatus.CANCELLED, offer.getStatus());
        List<AuditLog> audits = offerAudits(fixture.acceptedOfferId());
        assertEquals(1, audits.size());
        AuditLog audit = audits.getFirst();
        assertEquals("SlotOffer", audit.getEntityType());
        assertEquals(fixture.acceptedOfferId(), audit.getEntityId());
        assertEquals("CANCEL", audit.getAction());
        assertEquals("SUPERSEDED_BY_ACCEPTANCE", audit.getReason());
        assertEquals(ActorType.USER, audit.getActorType());
        assertEquals(fixture.patientId(), audit.getActorUserId());
    }

    @Test
    void cleanupLeavesResolvedOffersUntouched() {
        for (SlotOfferStatus status : List.of(
                SlotOfferStatus.DECLINED,
                SlotOfferStatus.CANCELLED,
                SlotOfferStatus.EXPIRED)) {
            DiscoveryFixture fixture = createFixture(status);
            SlotOffer offer = slotOfferRepository
                    .findById(fixture.acceptedOfferId())
                    .orElseThrow();
            Instant updatedAt = offer.getUpdatedAt();

            cleanupDiscovery.cleanupIfStillOffered(
                    offer,
                    "SUPERSEDED_BY_ACCEPTANCE",
                    ActorType.USER,
                    fixture.patientId());

            assertEquals(status, offer.getStatus());
            assertEquals(updatedAt, offer.getUpdatedAt());
            SlotOffer savedOffer = slotOfferRepository
                    .findById(fixture.acceptedOfferId())
                    .orElseThrow();
            assertEquals(status, savedOffer.getStatus());
            assertEquals(updatedAt, savedOffer.getUpdatedAt());
            assertTrue(offerAudits(fixture.acceptedOfferId()).isEmpty());
        }
    }

    private List<Long> discover(DiscoveryFixture fixture, List<Long> siblingEntryIds) {
        return cleanupDiscovery.discoverCleanupOfferIds(
                fixture.acceptedOfferId(),
                fixture.acceptedEntryId(),
                siblingEntryIds,
                fixture.patientId(),
                fixture.oldAppointmentId(),
                fixture.newStart(),
                fixture.newEnd());
    }

    private DiscoveryFixture createFixture(SlotOfferStatus acceptedOfferStatus) {
        long number = SEQUENCE.incrementAndGet();
        Long specialtyId = jdbcTemplate.queryForObject(
                "INSERT INTO specialty (name) VALUES (?) RETURNING id",
                Long.class,
                "Cleanup Specialty " + number);
        Long appointmentTypeId = jdbcTemplate.queryForObject(
                "INSERT INTO appointment_type "
                        + "(name, duration_minutes, specialty_id) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "Cleanup Type " + number,
                60,
                specialtyId);
        Long providerUserId = insertUser("cleanup-provider-" + number, "PROVIDER");
        Long patientId = insertUser("cleanup-patient-" + number, "PATIENT");
        Long providerId = jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "CLEANUP-" + number);
        Instant newStart = Instant.parse("2050-01-01T14:00:00Z")
                .plusSeconds(number * 86400);
        Long oldAppointmentId = insertAppointment(
                patientId,
                providerId,
                appointmentTypeId,
                newStart.minusSeconds(86400));
        Long acceptedEntryId = insertWaitlistEntry(
                patientId, oldAppointmentId, appointmentTypeId, "ACTIVE");
        Long acceptedSourceId = insertAppointment(
                patientId,
                providerId,
                appointmentTypeId,
                newStart.minusSeconds(172800));
        Long acceptedOfferId = insertOffer(
                acceptedEntryId, acceptedSourceId, acceptedOfferStatus);

        return new DiscoveryFixture(
                patientId,
                providerId,
                appointmentTypeId,
                oldAppointmentId,
                acceptedEntryId,
                acceptedOfferId,
                newStart,
                newStart.plusSeconds(3600));
    }

    private Long insertAppointment(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Instant startAt) {
        return jdbcTemplate.queryForObject(
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
    }

    private Long insertWaitlistEntry(
            Long patientId,
            Long appointmentId,
            Long appointmentTypeId,
            String status) {
        LocalDate startDate = LocalDate.of(2050, 1, 1).plusDays(SEQUENCE.incrementAndGet());
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
                status);
    }

    private Long insertOffer(
            Long waitlistEntryId,
            Long sourceAppointmentId,
            SlotOfferStatus status) {
        Long recoveryJobId = jdbcTemplate.queryForObject(
                "INSERT INTO recovery_job (source_appointment_id, status) "
                        + "VALUES (?, 'OPEN') RETURNING id",
                Long.class,
                sourceAppointmentId);
        return jdbcTemplate.queryForObject(
                "INSERT INTO slot_offer "
                        + "(recovery_job_id, waitlist_entry_id, status, expires_at) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                recoveryJobId,
                waitlistEntryId,
                status.name(),
                Timestamp.from(Instant.parse("2090-01-01T00:00:00Z")));
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

    private record DiscoveryFixture(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Long oldAppointmentId,
            Long acceptedEntryId,
            Long acceptedOfferId,
            Instant newStart,
            Instant newEnd) {
    }
}

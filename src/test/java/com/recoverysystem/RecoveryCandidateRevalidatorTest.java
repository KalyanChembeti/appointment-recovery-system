package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.domain.enums.TimeOfDayPreference;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import com.recoverysystem.service.RecoveryCandidateRevalidator;
import jakarta.persistence.EntityManager;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Lock;
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
class RecoveryCandidateRevalidatorTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Instant CREATED_AT = Instant.parse("2060-01-01T12:00:00Z");

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
    private RecoveryCandidateRevalidator candidateRevalidator;

    @Autowired
    private WaitlistEntryRepository waitlistEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void fullyEligibleCandidateReturnsTrue() {
        CandidateFixture fixture = createFullyEligibleCandidate();

        assertTrue(isStillEligible(fixture, lockCandidate(fixture)));
    }

    @Test
    void inactiveCandidateReturnsFalse() {
        CandidateFixture fixture = createFullyEligibleCandidate();
        WaitlistEntry candidate = detachedCandidate(fixture);
        candidate.setStatus(WaitlistEntryStatus.REMOVED);

        assertFalse(isStillEligible(fixture, candidate));
    }

    @Test
    void mismatchedAppointmentTypeReturnsFalse() {
        CandidateFixture fixture = createFullyEligibleCandidate();
        WaitlistEntry candidate = detachedCandidate(fixture);
        candidate.setAppointmentTypeId(
                insertAppointmentType(fixture.specialtyId(), "Different Type"));

        assertFalse(isStillEligible(fixture, candidate));
    }

    @Test
    void dateBeforeEarliestReturnsFalse() {
        CandidateFixture fixture = createFullyEligibleCandidate();
        WaitlistEntry candidate = detachedCandidate(fixture);
        candidate.setEarliestAppointmentDate(fixture.releasedLocalDate().plusDays(1));

        assertFalse(isStillEligible(fixture, candidate));
    }

    @Test
    void dateAfterLatestReturnsFalse() {
        CandidateFixture fixture = createFullyEligibleCandidate();
        WaitlistEntry candidate = detachedCandidate(fixture);
        candidate.setLatestAppointmentDate(fixture.releasedLocalDate().minusDays(1));

        assertFalse(isStillEligible(fixture, candidate));
    }

    @Test
    void bothDateBoundariesAreInclusive() {
        CandidateFixture fixture = createFullyEligibleCandidate();
        WaitlistEntry candidate = detachedCandidate(fixture);
        candidate.setEarliestAppointmentDate(fixture.releasedLocalDate());
        candidate.setLatestAppointmentDate(fixture.releasedLocalDate());

        assertTrue(isStillEligible(fixture, candidate));
    }

    @Test
    void missingAnchorReturnsFalse() {
        CandidateFixture fixture = createFullyEligibleCandidate();
        WaitlistEntry candidate = detachedCandidate(fixture);
        candidate.setCurrentAppointmentId(Long.MAX_VALUE);

        assertFalse(isStillEligible(fixture, candidate));
    }

    @Test
    void nonScheduledAnchorReturnsFalse() {
        CandidateFixture fixture = createFullyEligibleCandidate();
        jdbcTemplate.update(
                "UPDATE appointment SET status = 'CANCELLED', "
                        + "cancellation_reason = 'PATIENT_CANCELLED' WHERE id = ?",
                fixture.anchorAppointmentId());

        assertFalse(isStillEligible(fixture, lockCandidate(fixture)));
    }

    @Test
    void anchorPatientMismatchReturnsFalse() {
        CandidateFixture fixture = createFullyEligibleCandidate();
        Long differentPatientId = insertUser("different-anchor-patient", "PATIENT");
        jdbcTemplate.update(
                "UPDATE appointment SET patient_id = ? WHERE id = ?",
                differentPatientId,
                fixture.anchorAppointmentId());

        assertFalse(isStillEligible(fixture, lockCandidate(fixture)));
    }

    @Test
    void releasedIntervalAfterAnchorReturnsFalseWithoutPatientConflict() {
        CandidateFixture fixture = createFullyEligibleCandidate();
        Instant anchorStart = fixture.releasedStartAt().minusSeconds(7200);
        Instant anchorEnd = fixture.releasedStartAt().minusSeconds(3600);
        jdbcTemplate.update(
                "UPDATE appointment SET start_at = ?, end_at = ? WHERE id = ?",
                Timestamp.from(anchorStart),
                Timestamp.from(anchorEnd),
                fixture.anchorAppointmentId());

        Integer conflictCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM appointment "
                        + "WHERE patient_id = ? AND status = 'SCHEDULED' "
                        + "AND start_at < ? AND end_at > ?",
                Integer.class,
                fixture.patientId(),
                Timestamp.from(fixture.releasedEndAt()),
                Timestamp.from(fixture.releasedStartAt()));

        assertEquals(0, conflictCount.intValue());
        assertFalse(isStillEligible(fixture, lockCandidate(fixture)));
    }

    @Test
    void overlappingPatientAppointmentReturnsFalse() {
        CandidateFixture fixture = createFullyEligibleCandidate();
        Long otherProviderId = insertProvider(
                fixture.specialtyId(), "Patient Conflict Provider");
        insertAppointment(
                fixture.patientId(),
                otherProviderId,
                fixture.appointmentTypeId(),
                fixture.releasedStartAt().plusSeconds(300),
                fixture.releasedEndAt().minusSeconds(300),
                AppointmentStatus.SCHEDULED);

        assertFalse(isStillEligible(fixture, lockCandidate(fixture)));
    }

    @Test
    void priorOfferForSameJobThroughAnotherPatientEntryReturnsFalse() {
        CandidateFixture fixture = createFullyEligibleCandidate();
        Long otherEntryId = insertWaitlistEntry(
                fixture.patientId(),
                fixture.anchorAppointmentId(),
                fixture.appointmentTypeId(),
                fixture.releasedLocalDate().minusDays(7),
                fixture.releasedLocalDate().plusDays(7));
        insertSlotOffer(fixture.recoveryJobId(), otherEntryId, SlotOfferStatus.DECLINED);

        assertFalse(isStillEligible(fixture, lockCandidate(fixture)));
    }

    @Test
    void priorOfferForDifferentJobDoesNotExcludeCandidate() {
        CandidateFixture fixture = createFullyEligibleCandidate();
        Long otherSourceId = insertAppointment(
                insertUser("other-source-patient", "PATIENT"),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.releasedStartAt().plusSeconds(604_800),
                fixture.releasedEndAt().plusSeconds(604_800),
                AppointmentStatus.CANCELLED);
        Long otherJobId = insertRecoveryJob(otherSourceId);
        insertSlotOffer(otherJobId, fixture.waitlistEntryId(), SlotOfferStatus.DECLINED);

        assertTrue(isStillEligible(fixture, lockCandidate(fixture)));
    }

    @Test
    void offerForDifferentPatientOnSameJobDoesNotExcludeCandidate() {
        CandidateFixture fixture = createFullyEligibleCandidate();
        Long otherPatientId = insertUser("other-offer-patient", "PATIENT");
        Long otherAnchorId = insertAppointment(
                otherPatientId,
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.releasedEndAt().plusSeconds(172_800),
                fixture.releasedEndAt().plusSeconds(176_400),
                AppointmentStatus.SCHEDULED);
        Long otherEntryId = insertWaitlistEntry(
                otherPatientId,
                otherAnchorId,
                fixture.appointmentTypeId(),
                fixture.releasedLocalDate().minusDays(7),
                fixture.releasedLocalDate().plusDays(7));
        insertSlotOffer(fixture.recoveryJobId(), otherEntryId, SlotOfferStatus.DECLINED);

        assertTrue(isStillEligible(fixture, lockCandidate(fixture)));
    }

    @Test
    void revalidatorAndNewRepositoryQueriesDoNotDeclareTransactionsOrLocks()
            throws NoSuchMethodException {
        assertNull(RecoveryCandidateRevalidator.class.getAnnotation(Transactional.class));
        assertNull(RecoveryCandidateRevalidator.class
                .getDeclaredMethod(
                        "isStillEligible",
                        WaitlistEntry.class,
                        Long.class,
                        Long.class,
                        LocalDate.class,
                        Instant.class,
                        Instant.class)
                .getAnnotation(Transactional.class));
        assertNull(AppointmentRepository.class
                .getMethod(
                        "findScheduledOverlappingIdsForPatient",
                        Long.class,
                        Instant.class,
                        Instant.class)
                .getAnnotation(Lock.class));
        assertNull(SlotOfferRepository.class
                .getMethod(
                        "existsPriorOfferForPatientAndJob", Long.class, Long.class)
                .getAnnotation(Lock.class));
    }

    @Test
    void successfulCheckDoesNotChangeCandidateOrDatabase() {
        CandidateFixture fixture = createFullyEligibleCandidate();
        WaitlistEntry candidate = lockCandidate(fixture);
        Instant updatedAt = candidate.getUpdatedAt();
        Integer appointmentCount = countRows("appointment");
        Integer waitlistCount = countRows("waitlist_entry");
        Integer offerCount = countRows("slot_offer");

        assertTrue(isStillEligible(fixture, candidate));

        assertEquals(WaitlistEntryStatus.ACTIVE, candidate.getStatus());
        assertEquals(updatedAt, candidate.getUpdatedAt());
        assertEquals(appointmentCount, countRows("appointment"));
        assertEquals(waitlistCount, countRows("waitlist_entry"));
        assertEquals(offerCount, countRows("slot_offer"));
    }

    private boolean isStillEligible(CandidateFixture fixture, WaitlistEntry candidate) {
        return candidateRevalidator.isStillEligible(
                candidate,
                fixture.recoveryJobId(),
                fixture.appointmentTypeId(),
                fixture.releasedLocalDate(),
                fixture.releasedStartAt(),
                fixture.releasedEndAt());
    }

    private WaitlistEntry lockCandidate(CandidateFixture fixture) {
        return waitlistEntryRepository
                .findByIdForUpdate(fixture.waitlistEntryId())
                .orElseThrow();
    }

    private WaitlistEntry detachedCandidate(CandidateFixture fixture) {
        WaitlistEntry candidate = lockCandidate(fixture);
        entityManager.detach(candidate);
        return candidate;
    }

    private CandidateFixture createFullyEligibleCandidate() {
        long number = SEQUENCE.incrementAndGet();
        Long specialtyId = insertSpecialty("Revalidator Specialty " + number);
        Long appointmentTypeId = insertAppointmentType(
                specialtyId, "Revalidator Appointment Type " + number);
        Long providerId = insertProvider(specialtyId, "Released Provider " + number);
        Instant startAt = Instant.parse("2080-06-15T14:00:00Z")
                .plusSeconds(number * 86_400);
        Instant endAt = startAt.plusSeconds(3600);
        Long sourceAppointmentId = insertAppointment(
                insertUser("released-source-patient-" + number, "PATIENT"),
                providerId,
                appointmentTypeId,
                startAt,
                endAt,
                AppointmentStatus.CANCELLED);
        Long recoveryJobId = insertRecoveryJob(sourceAppointmentId);

        Long patientId = insertUser("revalidator-candidate-" + number, "PATIENT");
        Long anchorProviderId = insertProvider(specialtyId, "Anchor Provider " + number);
        Long anchorAppointmentId = insertAppointment(
                patientId,
                anchorProviderId,
                appointmentTypeId,
                endAt.plusSeconds(86_400),
                endAt.plusSeconds(90_000),
                AppointmentStatus.SCHEDULED);
        LocalDate localDate = LocalDate.of(2080, 6, 15).plusDays(number);
        Long waitlistEntryId = insertWaitlistEntry(
                patientId,
                anchorAppointmentId,
                appointmentTypeId,
                localDate.minusDays(7),
                localDate.plusDays(7));

        return new CandidateFixture(
                recoveryJobId,
                specialtyId,
                appointmentTypeId,
                providerId,
                patientId,
                anchorAppointmentId,
                waitlistEntryId,
                localDate,
                startAt,
                endAt);
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
        Long providerUserId = insertUser("revalidator-provider-" + number, "PROVIDER");
        return jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "REVALIDATOR-" + name + "-" + number);
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

    private Long insertWaitlistEntry(
            Long patientId,
            Long anchorAppointmentId,
            Long appointmentTypeId,
            LocalDate earliestDate,
            LocalDate latestDate) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO waitlist_entry "
                        + "(patient_id, current_appointment_id, appointment_type_id, "
                        + "earliest_appointment_date, latest_appointment_date, "
                        + "preferred_time_of_day, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                patientId,
                anchorAppointmentId,
                appointmentTypeId,
                Date.valueOf(earliestDate),
                Date.valueOf(latestDate),
                TimeOfDayPreference.ANY.name(),
                WaitlistEntryStatus.ACTIVE.name(),
                Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT));
    }

    private Long insertRecoveryJob(Long sourceAppointmentId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO recovery_job (source_appointment_id, status) "
                        + "VALUES (?, 'OPEN') RETURNING id",
                Long.class,
                sourceAppointmentId);
    }

    private void insertSlotOffer(
            Long recoveryJobId, Long waitlistEntryId, SlotOfferStatus status) {
        jdbcTemplate.update(
                "INSERT INTO slot_offer "
                        + "(recovery_job_id, waitlist_entry_id, status, expires_at) "
                        + "VALUES (?, ?, ?, ?)",
                recoveryJobId,
                waitlistEntryId,
                status.name(),
                Timestamp.from(Instant.now().plusSeconds(3600)));
    }

    private Integer countRows(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }

    private record CandidateFixture(
            Long recoveryJobId,
            Long specialtyId,
            Long appointmentTypeId,
            Long providerId,
            Long patientId,
            Long anchorAppointmentId,
            Long waitlistEntryId,
            LocalDate releasedLocalDate,
            Instant releasedStartAt,
            Instant releasedEndAt) {
    }
}

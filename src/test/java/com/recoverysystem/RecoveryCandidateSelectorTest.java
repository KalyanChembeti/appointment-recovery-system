package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.domain.enums.TimeOfDayPreference;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.repository.WaitlistEntryRepository;
import com.recoverysystem.service.RecoveryCandidateSelector;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
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
class RecoveryCandidateSelectorTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ZoneId CLINIC_TIME_ZONE = ZoneId.of("America/New_York");
    private static final Instant DEFAULT_CREATED_AT = Instant.parse("2060-01-01T12:00:00Z");

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
    private RecoveryCandidateSelector candidateSelector;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void findsBasicEligibleCandidate() throws NoSuchMethodException {
        ReleaseFixture fixture = createReleaseFixture();
        Candidate candidate = insertEligibleCandidate(
                fixture, DEFAULT_CREATED_AT, TimeOfDayPreference.ANY, null);

        Optional<Long> result = selectCandidate(fixture);

        assertEquals(Optional.of(candidate.entryId()), result);
        assertNull(RecoveryCandidateSelector.class.getAnnotation(Transactional.class));
        assertNull(RecoveryCandidateSelector.class
                .getDeclaredMethod(
                        "selectTopCandidate",
                        Long.class,
                        Long.class,
                        Long.class,
                        Instant.class,
                        Instant.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void removedAndFulfilledEntriesAreExcluded() {
        ReleaseFixture fixture = createReleaseFixture();
        insertCandidate(
                fixture,
                WaitlistEntryStatus.REMOVED,
                fixture.appointmentTypeId(),
                fixture.localDate().minusDays(1),
                fixture.localDate().plusDays(1),
                TimeOfDayPreference.ANY,
                null,
                DEFAULT_CREATED_AT,
                AppointmentStatus.SCHEDULED,
                fixture.endAt().plusSeconds(86_400));
        insertCandidate(
                fixture,
                WaitlistEntryStatus.FULFILLED,
                fixture.appointmentTypeId(),
                fixture.localDate().minusDays(1),
                fixture.localDate().plusDays(1),
                TimeOfDayPreference.ANY,
                null,
                DEFAULT_CREATED_AT.plusSeconds(1),
                AppointmentStatus.SCHEDULED,
                fixture.endAt().plusSeconds(86_400));

        assertTrue(selectCandidate(fixture).isEmpty());
    }

    @Test
    void entryWithCancelledAnchorIsExcluded() {
        ReleaseFixture fixture = createReleaseFixture();
        insertCandidate(
                fixture,
                WaitlistEntryStatus.ACTIVE,
                fixture.appointmentTypeId(),
                fixture.localDate().minusDays(1),
                fixture.localDate().plusDays(1),
                TimeOfDayPreference.ANY,
                null,
                DEFAULT_CREATED_AT,
                AppointmentStatus.CANCELLED,
                fixture.endAt().plusSeconds(86_400));

        assertTrue(selectCandidate(fixture).isEmpty());
    }

    @Test
    void mismatchedAppointmentTypeIsExcluded() {
        ReleaseFixture fixture = createReleaseFixture();
        Long otherAppointmentTypeId = insertAppointmentType(
                fixture.specialtyId(), "Other Candidate Type");
        insertCandidate(
                fixture,
                WaitlistEntryStatus.ACTIVE,
                otherAppointmentTypeId,
                fixture.localDate().minusDays(1),
                fixture.localDate().plusDays(1),
                TimeOfDayPreference.ANY,
                null,
                DEFAULT_CREATED_AT,
                AppointmentStatus.SCHEDULED,
                fixture.endAt().plusSeconds(86_400));

        assertTrue(selectCandidate(fixture).isEmpty());
    }

    @Test
    void dateWindowIsInclusiveAndRejectsDatesOutsideIt() {
        ReleaseFixture fixture = createReleaseFixture();
        insertCandidate(
                fixture,
                WaitlistEntryStatus.ACTIVE,
                fixture.appointmentTypeId(),
                fixture.localDate().plusDays(1),
                fixture.localDate().plusDays(3),
                TimeOfDayPreference.ANY,
                null,
                DEFAULT_CREATED_AT,
                AppointmentStatus.SCHEDULED,
                fixture.endAt().plusSeconds(86_400));
        insertCandidate(
                fixture,
                WaitlistEntryStatus.ACTIVE,
                fixture.appointmentTypeId(),
                fixture.localDate().minusDays(3),
                fixture.localDate().minusDays(1),
                TimeOfDayPreference.ANY,
                null,
                DEFAULT_CREATED_AT.plusSeconds(1),
                AppointmentStatus.SCHEDULED,
                fixture.endAt().plusSeconds(86_400));
        Candidate boundaryCandidate = insertCandidate(
                fixture,
                WaitlistEntryStatus.ACTIVE,
                fixture.appointmentTypeId(),
                fixture.localDate(),
                fixture.localDate(),
                TimeOfDayPreference.ANY,
                null,
                DEFAULT_CREATED_AT.plusSeconds(2),
                AppointmentStatus.SCHEDULED,
                fixture.endAt().plusSeconds(86_400));

        assertEquals(Optional.of(boundaryCandidate.entryId()), selectCandidate(fixture));
    }

    @Test
    void patientScheduleConflictExcludesCandidate() {
        ReleaseFixture fixture = createReleaseFixture();
        Candidate candidate = insertEligibleCandidate(
                fixture, DEFAULT_CREATED_AT, TimeOfDayPreference.ANY, null);
        Long conflictProviderId = insertProvider(
                fixture.specialtyId(), "Conflict Provider");
        insertAppointment(
                candidate.patientId(),
                conflictProviderId,
                fixture.appointmentTypeId(),
                fixture.startAt().plusSeconds(300),
                fixture.endAt().minusSeconds(300),
                AppointmentStatus.SCHEDULED);

        assertTrue(selectCandidate(fixture).isEmpty());
    }

    @Test
    void priorOfferForSameJobExcludesPatientButDifferentJobDoesNot() {
        ReleaseFixture fixture = createReleaseFixture();
        Candidate excludedCandidate = insertEligibleCandidate(
                fixture, DEFAULT_CREATED_AT, TimeOfDayPreference.ANY, null);
        Long otherEntryForSamePatient = insertWaitlistEntry(
                excludedCandidate.patientId(),
                excludedCandidate.anchorAppointmentId(),
                fixture.appointmentTypeId(),
                fixture.localDate().minusDays(1),
                fixture.localDate().plusDays(1),
                TimeOfDayPreference.ANY,
                null,
                WaitlistEntryStatus.ACTIVE,
                DEFAULT_CREATED_AT.plusSeconds(1));
        insertSlotOffer(
                fixture.recoveryJobId(), otherEntryForSamePatient, SlotOfferStatus.DECLINED);

        Candidate eligibleCandidate = insertEligibleCandidate(
                fixture, DEFAULT_CREATED_AT.plusSeconds(2), TimeOfDayPreference.ANY, null);
        Long otherSourceAppointmentId = insertAppointment(
                eligibleCandidate.patientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                fixture.startAt().plusSeconds(604_800),
                fixture.endAt().plusSeconds(604_800),
                AppointmentStatus.CANCELLED);
        Long otherRecoveryJobId = insertRecoveryJob(otherSourceAppointmentId);
        insertSlotOffer(
                otherRecoveryJobId, eligibleCandidate.entryId(), SlotOfferStatus.DECLINED);

        assertEquals(Optional.of(eligibleCandidate.entryId()), selectCandidate(fixture));
    }

    @Test
    void overlappingAnchorIsExcludedByStrictEndBeforeStartRule() throws NoSuchMethodException {
        ReleaseFixture fixture = createReleaseFixture();
        Instant anchorStart = fixture.startAt().plusSeconds(1800);
        assertTrue(fixture.startAt().isBefore(anchorStart));
        assertTrue(fixture.endAt().isAfter(anchorStart));
        insertCandidate(
                fixture,
                WaitlistEntryStatus.ACTIVE,
                fixture.appointmentTypeId(),
                fixture.localDate().minusDays(1),
                fixture.localDate().plusDays(1),
                TimeOfDayPreference.ANY,
                null,
                DEFAULT_CREATED_AT,
                AppointmentStatus.SCHEDULED,
                anchorStart);

        assertTrue(selectCandidate(fixture).isEmpty());

        Query query = WaitlistEntryRepository.class
                .getMethod(
                        "findRankedEligibleCandidateIds",
                        Long.class,
                        Long.class,
                        Long.class,
                        LocalDate.class,
                        Instant.class,
                        Instant.class,
                        boolean.class,
                        Pageable.class)
                .getAnnotation(Query.class);
        assertTrue(query.value().contains("AND :releasedEndAt <= anchor.startAt"));
    }

    @Test
    void releasedIntervalAfterAnchorIsExcludedWithoutConflictCoFiring() {
        ReleaseFixture fixture = createReleaseFixture();
        Instant anchorStart = fixture.startAt().minusSeconds(7200);
        Instant anchorEnd = anchorStart.plusSeconds(3600);
        assertFalse(anchorEnd.isAfter(fixture.startAt()));

        Candidate candidate = insertCandidate(
                fixture,
                WaitlistEntryStatus.ACTIVE,
                fixture.appointmentTypeId(),
                fixture.localDate().minusDays(1),
                fixture.localDate().plusDays(1),
                TimeOfDayPreference.ANY,
                null,
                DEFAULT_CREATED_AT,
                AppointmentStatus.SCHEDULED,
                anchorStart);

        Integer conflictCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM appointment "
                        + "WHERE patient_id = ? AND status = 'SCHEDULED' "
                        + "AND start_at < ? AND end_at > ?",
                Integer.class,
                candidate.patientId(),
                Timestamp.from(fixture.endAt()),
                Timestamp.from(fixture.startAt()));

        assertEquals(0, conflictCount.intValue());
        assertTrue(selectCandidate(fixture).isEmpty());
    }

    @Test
    void olderCandidateWinsFifoRanking() {
        ReleaseFixture fixture = createReleaseFixture();
        Candidate newerCandidate = insertEligibleCandidate(
                fixture,
                DEFAULT_CREATED_AT.plusSeconds(3600),
                TimeOfDayPreference.ANY,
                null);
        Candidate olderCandidate = insertEligibleCandidate(
                fixture, DEFAULT_CREATED_AT, TimeOfDayPreference.ANY, null);
        assertTrue(newerCandidate.entryId() < olderCandidate.entryId());

        assertEquals(Optional.of(olderCandidate.entryId()), selectCandidate(fixture));
    }

    @Test
    void matchingTimePreferenceWinsCreatedAtTie() {
        ReleaseFixture fixture = createReleaseFixture();
        assertTrue(fixture.startAt()
                .atZone(CLINIC_TIME_ZONE)
                .toLocalTime()
                .isBefore(java.time.LocalTime.NOON));
        Candidate mismatchedCandidate = insertEligibleCandidate(
                fixture,
                DEFAULT_CREATED_AT,
                TimeOfDayPreference.AFTERNOON,
                null);
        Candidate matchingCandidate = insertEligibleCandidate(
                fixture,
                DEFAULT_CREATED_AT,
                TimeOfDayPreference.MORNING,
                null);
        assertTrue(mismatchedCandidate.entryId() < matchingCandidate.entryId());

        assertEquals(Optional.of(matchingCandidate.entryId()), selectCandidate(fixture));
    }

    @Test
    void anyPreferenceRanksWithExactMatchAndAheadOfMismatch() {
        ReleaseFixture fixture = createReleaseFixture();
        Candidate anyCandidate = insertEligibleCandidate(
                fixture, DEFAULT_CREATED_AT, TimeOfDayPreference.ANY, null);
        insertEligibleCandidate(
                fixture, DEFAULT_CREATED_AT, TimeOfDayPreference.AFTERNOON, null);
        insertEligibleCandidate(
                fixture, DEFAULT_CREATED_AT, TimeOfDayPreference.MORNING, null);

        assertEquals(Optional.of(anyCandidate.entryId()), selectCandidate(fixture));
    }

    @Test
    void matchingAndNullProviderPreferencesRankAheadOfDifferentProvider() {
        ReleaseFixture fixture = createReleaseFixture();
        Long differentProviderId = insertProvider(
                fixture.specialtyId(), "Different Preferred Provider");
        insertEligibleCandidate(
                fixture,
                DEFAULT_CREATED_AT,
                TimeOfDayPreference.MORNING,
                differentProviderId);
        Candidate matchingProviderCandidate = insertEligibleCandidate(
                fixture,
                DEFAULT_CREATED_AT,
                TimeOfDayPreference.MORNING,
                fixture.providerId());
        Candidate noPreferenceCandidate = insertEligibleCandidate(
                fixture, DEFAULT_CREATED_AT, TimeOfDayPreference.MORNING, null);
        assertTrue(matchingProviderCandidate.entryId() < noPreferenceCandidate.entryId());

        assertEquals(
                Optional.of(matchingProviderCandidate.entryId()), selectCandidate(fixture));
    }

    @Test
    void lowerIdWinsWhenAllRankingValuesTie() {
        ReleaseFixture fixture = createReleaseFixture();
        Candidate firstCandidate = insertEligibleCandidate(
                fixture, DEFAULT_CREATED_AT, TimeOfDayPreference.MORNING, null);
        Candidate secondCandidate = insertEligibleCandidate(
                fixture, DEFAULT_CREATED_AT, TimeOfDayPreference.MORNING, null);
        assertTrue(firstCandidate.entryId() < secondCandidate.entryId());

        assertEquals(Optional.of(firstCandidate.entryId()), selectCandidate(fixture));
    }

    @Test
    void returnsEmptyWhenNoCandidateIsEligible() {
        ReleaseFixture fixture = createReleaseFixture();

        assertFalse(selectCandidate(fixture).isPresent());
    }

    private Optional<Long> selectCandidate(ReleaseFixture fixture) {
        return candidateSelector.selectTopCandidate(
                fixture.recoveryJobId(),
                fixture.appointmentTypeId(),
                fixture.providerId(),
                fixture.startAt(),
                fixture.endAt());
    }

    private ReleaseFixture createReleaseFixture() {
        long number = SEQUENCE.incrementAndGet();
        Long specialtyId = insertSpecialty("Released Specialty " + number);
        Long appointmentTypeId = insertAppointmentType(
                specialtyId, "Released Appointment Type " + number);
        Long providerId = insertProvider(specialtyId, "Released Provider " + number);
        Long patientId = insertUser("released-patient-" + number, "PATIENT");
        Instant startAt = Instant.parse("2070-06-15T14:00:00Z")
                .plusSeconds(number * 86_400);
        Instant endAt = startAt.plusSeconds(3600);
        Long sourceAppointmentId = insertAppointment(
                patientId,
                providerId,
                appointmentTypeId,
                startAt,
                endAt,
                AppointmentStatus.CANCELLED);
        Long recoveryJobId = insertRecoveryJob(sourceAppointmentId);
        LocalDate localDate = startAt.atZone(CLINIC_TIME_ZONE).toLocalDate();
        return new ReleaseFixture(
                recoveryJobId,
                specialtyId,
                appointmentTypeId,
                providerId,
                startAt,
                endAt,
                localDate);
    }

    private Candidate insertEligibleCandidate(
            ReleaseFixture fixture,
            Instant createdAt,
            TimeOfDayPreference timePreference,
            Long preferredProviderId) {
        return insertCandidate(
                fixture,
                WaitlistEntryStatus.ACTIVE,
                fixture.appointmentTypeId(),
                fixture.localDate().minusDays(7),
                fixture.localDate().plusDays(7),
                timePreference,
                preferredProviderId,
                createdAt,
                AppointmentStatus.SCHEDULED,
                fixture.endAt().plusSeconds(86_400));
    }

    private Candidate insertCandidate(
            ReleaseFixture fixture,
            WaitlistEntryStatus entryStatus,
            Long entryAppointmentTypeId,
            LocalDate earliestDate,
            LocalDate latestDate,
            TimeOfDayPreference timePreference,
            Long preferredProviderId,
            Instant createdAt,
            AppointmentStatus anchorStatus,
            Instant anchorStart) {
        long number = SEQUENCE.incrementAndGet();
        Long patientId = insertUser("candidate-patient-" + number, "PATIENT");
        Long anchorProviderId = insertProvider(
                fixture.specialtyId(), "Anchor Provider " + number);
        Long anchorAppointmentId = insertAppointment(
                patientId,
                anchorProviderId,
                fixture.appointmentTypeId(),
                anchorStart,
                anchorStart.plusSeconds(3600),
                anchorStatus);
        Long entryId = insertWaitlistEntry(
                patientId,
                anchorAppointmentId,
                entryAppointmentTypeId,
                earliestDate,
                latestDate,
                timePreference,
                preferredProviderId,
                entryStatus,
                createdAt);
        return new Candidate(entryId, patientId, anchorAppointmentId);
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
        Long providerUserId = insertUser("provider-" + number, "PROVIDER");
        return jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "CANDIDATE-" + name + "-" + number);
    }

    private Long insertUser(String emailPrefix, String role) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, role) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                emailPrefix + "@example.com",
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
            LocalDate latestDate,
            TimeOfDayPreference timePreference,
            Long preferredProviderId,
            WaitlistEntryStatus status,
            Instant createdAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO waitlist_entry "
                        + "(patient_id, current_appointment_id, appointment_type_id, "
                        + "preferred_provider_id, earliest_appointment_date, "
                        + "latest_appointment_date, preferred_time_of_day, status, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                patientId,
                anchorAppointmentId,
                appointmentTypeId,
                preferredProviderId,
                Date.valueOf(earliestDate),
                Date.valueOf(latestDate),
                timePreference.name(),
                status.name(),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
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

    private record ReleaseFixture(
            Long recoveryJobId,
            Long specialtyId,
            Long appointmentTypeId,
            Long providerId,
            Instant startAt,
            Instant endAt,
            LocalDate localDate) {
    }

    private record Candidate(Long entryId, Long patientId, Long anchorAppointmentId) {
    }
}

package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.domain.enums.RecoveryWorkerOutcome;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.service.RecoveryCandidateSelector;
import com.recoverysystem.service.RecoveryWorkerService;
import com.recoverysystem.service.WaitlistEntryRemovalService;
import com.recoverysystem.support.ConcurrentRaceHarness;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class RaceMatrixCandidateRemovalTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ZoneId CLINIC_TIME_ZONE = ZoneId.of("America/New_York");
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FINISH_TIMEOUT = Duration.ofSeconds(20);

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
    private RecoveryCandidateSelector recoveryCandidateSelector;

    @Autowired
    private WaitlistEntryRemovalService waitlistEntryRemovalService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearWorkflowData() {
        org.mockito.Mockito.reset(recoveryCandidateSelector);
        jdbcTemplate.execute("TRUNCATE TABLE audit_log, slot_offer, recovery_job, "
                + "waitlist_entry, appointment, provider_schedule, provider_unavailability, "
                + "provider, appointment_type, specialty, users RESTART IDENTITY CASCADE");
        jdbcTemplate.update("UPDATE scheduling_policy SET "
                + "minimum_recovery_lead_minutes = 30, offer_duration_minutes = 10");
    }

    @Test
    void recoveryGenerationVsWaitlistEntryRemovalConverges() {
        CandidateRemovalFixture fixture = createFixture();

        ConcurrentRaceHarness.RaceResult<Object> result = ConcurrentRaceHarness.race(
                recoveryWorkerService::attemptRecovery,
                () -> waitlistEntryRemovalService.removeWaitlistEntry(
                        fixture.waitlistEntryId(), fixture.actorUserId()),
                READY_TIMEOUT,
                FINISH_TIMEOUT);

        RecoveryWorkerOutcome workerOutcome =
                assertInstanceOf(RecoveryWorkerOutcome.class, result.first());
        assertInstanceOf(WaitlistEntry.class, result.second());
        assertTrue(Set.of(
                        RecoveryWorkerOutcome.OFFER_CREATED,
                        RecoveryWorkerOutcome.CANDIDATE_BECAME_STALE,
                        RecoveryWorkerOutcome.NO_ELIGIBLE_CANDIDATE)
                .contains(workerOutcome));

        assertEquals(
                WaitlistEntryStatus.REMOVED.name(),
                textValue("SELECT status FROM waitlist_entry WHERE id = ?",
                        fixture.waitlistEntryId()));

        List<OfferState> offers = jdbcTemplate.query(
                "SELECT id, status FROM slot_offer WHERE recovery_job_id = ? ORDER BY id",
                (resultSet, rowNumber) -> new OfferState(
                        resultSet.getLong("id"), resultSet.getString("status")),
                fixture.recoveryJobId());
        assertTrue(offers.size() <= 1);
        assertTrue(offers.stream()
                .noneMatch(offer -> SlotOfferStatus.OFFERED.name().equals(offer.status())));
        assertTrue(offers.stream()
                .allMatch(offer -> SlotOfferStatus.CANCELLED.name().equals(offer.status())));
        assertEquals(
                offers.size(),
                count("SELECT COUNT(*) FROM audit_log "
                                + "WHERE entity_type = 'SlotOffer' AND action = 'CANCEL' "
                                + "AND reason = 'ENTRY_REMOVED' "
                                + "AND entity_id IN (SELECT id FROM slot_offer "
                                + "WHERE recovery_job_id = ?)",
                        fixture.recoveryJobId()));

        assertTrue(Set.of(RecoveryJobStatus.OPEN.name(), RecoveryJobStatus.EXHAUSTED.name())
                .contains(textValue("SELECT status FROM recovery_job WHERE id = ?",
                        fixture.recoveryJobId())));

        System.out.println("RM-11 worker outcome: " + workerOutcome
                + "; final offers: " + offers.stream().map(OfferState::status).toList());
    }

    @Test
    void candidateBecomesStaleWhenRemovedDuringSelectionWindow() {
        CandidateRemovalFixture fixture = createFixture();
        CountDownLatch candidateSelected = new CountDownLatch(1);

        // This is the second use of @SpyBean in this codebase for the same justified reason as
        // RecoveryWorkerServiceTest's existing spy usage -- the CANDIDATE_BECAME_STALE window sits
        // entirely inside one production method's execution and cannot be reliably reached via
        // external wall-clock timing (confirmed by the RM-10 investigation in
        // RaceMatrixProviderBlockTest). The spy calls the REAL selection logic and only delays the
        // return, making this a genuine forced-timing technique, not a faked outcome.
        org.mockito.Mockito.doAnswer(invocation -> {
                    Object selectedCandidate = invocation.callRealMethod();
                    candidateSelected.countDown();
                    Thread.sleep(500);
                    return selectedCandidate;
                })
                .when(recoveryCandidateSelector)
                .selectTopCandidate(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(Instant.class),
                        org.mockito.ArgumentMatchers.any(Instant.class));

        ConcurrentRaceHarness.RaceResult<Object> result = ConcurrentRaceHarness.race(
                recoveryWorkerService::attemptRecovery,
                () -> {
                    if (!candidateSelected.await(
                            READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new AssertionError(
                                "Recovery worker did not select the candidate within "
                                        + READY_TIMEOUT);
                    }
                    return waitlistEntryRemovalService.removeWaitlistEntry(
                            fixture.waitlistEntryId(), fixture.actorUserId());
                },
                READY_TIMEOUT,
                FINISH_TIMEOUT);

        assertEquals(
                RecoveryWorkerOutcome.CANDIDATE_BECAME_STALE,
                assertInstanceOf(RecoveryWorkerOutcome.class, result.first()));
        assertInstanceOf(WaitlistEntry.class, result.second());
        assertEquals(
                RecoveryJobStatus.OPEN.name(),
                textValue("SELECT status FROM recovery_job WHERE id = ?",
                        fixture.recoveryJobId()));
        assertEquals(
                0L,
                count("SELECT COUNT(*) FROM slot_offer WHERE recovery_job_id = ?",
                        fixture.recoveryJobId()));
        assertEquals(
                WaitlistEntryStatus.REMOVED.name(),
                textValue("SELECT status FROM waitlist_entry WHERE id = ?",
                        fixture.waitlistEntryId()));

        System.out.println("RM-11 forced outcome: "
                + RecoveryWorkerOutcome.CANDIDATE_BECAME_STALE);
    }

    private CandidateRemovalFixture createFixture() {
        long number = SEQUENCE.incrementAndGet();
        LocalDate releasedDate = LocalDate.of(2077, 3, 10).plusDays(number);
        Instant releasedStart = releasedDate.atTime(10, 0)
                .atZone(CLINIC_TIME_ZONE)
                .toInstant();
        Instant releasedEnd = releasedStart.plusSeconds(3600);
        Long specialtyId = insertSpecialty("RM-11 Specialty " + number);
        Long appointmentTypeId = insertAppointmentType(
                specialtyId, "RM-11 Appointment Type " + number);
        Long releasedProviderId = insertProvider(specialtyId, "released-" + number);
        Long sourcePatientId = insertUser("rm11-source-patient-" + number, "PATIENT");
        Long sourceAppointmentId = insertAppointment(
                sourcePatientId,
                releasedProviderId,
                appointmentTypeId,
                releasedStart,
                releasedEnd,
                "CANCELLED");
        Long recoveryJobId = jdbcTemplate.queryForObject(
                "INSERT INTO recovery_job (source_appointment_id, status) "
                        + "VALUES (?, 'OPEN') RETURNING id",
                Long.class,
                sourceAppointmentId);

        Long candidatePatientId = insertUser("rm11-candidate-patient-" + number, "PATIENT");
        Long anchorProviderId = insertProvider(specialtyId, "anchor-" + number);
        Instant anchorStart = releasedStart.plusSeconds(604_800);
        Long anchorAppointmentId = insertAppointment(
                candidatePatientId,
                anchorProviderId,
                appointmentTypeId,
                anchorStart,
                anchorStart.plusSeconds(3600),
                "SCHEDULED");
        Long waitlistEntryId = jdbcTemplate.queryForObject(
                "INSERT INTO waitlist_entry "
                        + "(patient_id, current_appointment_id, appointment_type_id, "
                        + "earliest_appointment_date, latest_appointment_date, "
                        + "preferred_time_of_day, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ANY', 'ACTIVE') RETURNING id",
                Long.class,
                candidatePatientId,
                anchorAppointmentId,
                appointmentTypeId,
                Date.valueOf(releasedDate.minusDays(7)),
                Date.valueOf(releasedDate.plusDays(7)));
        Long actorUserId = insertUser("rm11-receptionist-" + number, "RECEPTIONIST");

        return new CandidateRemovalFixture(recoveryJobId, waitlistEntryId, actorUserId);
    }

    private Long insertSpecialty(String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO specialty (name) VALUES (?) RETURNING id", Long.class, name);
    }

    private Long insertAppointmentType(Long specialtyId, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO appointment_type "
                        + "(name, duration_minutes, specialty_id) VALUES (?, 60, ?) RETURNING id",
                Long.class,
                name,
                specialtyId);
    }

    private Long insertProvider(Long specialtyId, String name) {
        Long providerUserId = insertUser("rm11-provider-" + name, "PROVIDER");
        return jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "RM-11-" + name);
    }

    private Long insertUser(String emailPrefix, String role) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, role) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                emailPrefix + "-" + SEQUENCE.incrementAndGet() + "@example.com",
                "test-password-hash",
                role);
    }

    private Long insertAppointment(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Instant startAt,
            Instant endAt,
            String status) {
        String cancellationReason = "CANCELLED".equals(status) ? "PATIENT_CANCELLED" : null;
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
                status,
                cancellationReason);
    }

    private long count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Long.class, arguments);
    }

    private String textValue(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, String.class, arguments);
    }

    private record OfferState(Long id, String status) {
    }

    private record CandidateRemovalFixture(
            Long recoveryJobId, Long waitlistEntryId, Long actorUserId) {
    }
}

package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.CancellationReason;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.exception.AppointmentNotScheduledException;
import com.recoverysystem.exception.OfferAlreadyAcceptedException;
import com.recoverysystem.exception.OfferAlreadyResolvedException;
import com.recoverysystem.exception.OfferExpiredException;
import com.recoverysystem.exception.SlotOfferNotOfferedException;
import com.recoverysystem.service.OfferAcceptanceOrchestrator;
import com.recoverysystem.service.SlotOfferDeclineService;
import com.recoverysystem.service.SlotOfferExpiryOfferProcessor;
import com.recoverysystem.support.ConcurrentRaceHarness;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
class RaceMatrixSlotOfferTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
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
    private OfferAcceptanceOrchestrator offerAcceptanceOrchestrator;

    @Autowired
    private SlotOfferDeclineService slotOfferDeclineService;

    @Autowired
    private SlotOfferExpiryOfferProcessor slotOfferExpiryOfferProcessor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearWorkflowData() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_log, slot_offer, recovery_job, "
                + "waitlist_entry, appointment, provider_schedule, provider_unavailability, "
                + "provider, appointment_type, specialty, users RESTART IDENTITY CASCADE");
    }

    @Test
    void duplicateAcceptRaceProducesExactlyOneWinner() {
        OfferFixture fixture = createOfferFixture(Duration.ofHours(1));
        long auditMarker = latestAuditId();

        ConcurrentRaceHarness.RaceResult<RaceOutcome> race = ConcurrentRaceHarness.race(
                () -> attemptAcceptance(fixture),
                () -> attemptAcceptance(fixture),
                READY_TIMEOUT,
                FINISH_TIMEOUT);

        List<RaceOutcome> outcomes = race.asList();
        assertEquals(1, outcomes.stream().filter(Accepted.class::isInstance).count());
        assertEquals(1, outcomes.stream().filter(ExpectedFailure.class::isInstance).count());

        RuntimeException loser = outcomes.stream()
                .filter(ExpectedFailure.class::isInstance)
                .map(ExpectedFailure.class::cast)
                .map(ExpectedFailure::exception)
                .findFirst()
                .orElseThrow();
        assertTrue(
                loser instanceof AppointmentNotScheduledException
                        || loser instanceof OfferAlreadyAcceptedException
                        || loser instanceof OfferAlreadyResolvedException
                        || loser instanceof OfferExpiredException,
                () -> "Unexpected duplicate-accept loser: " + loser.getClass().getName());

        Long newAppointmentId = assertAcceptanceState(fixture);
        Accepted winner = outcomes.stream()
                .filter(Accepted.class::isInstance)
                .map(Accepted.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(newAppointmentId, winner.appointmentId());
        assertSuccessfulAcceptanceAudits(fixture, newAppointmentId, auditMarker);
    }

    @Test
    void acceptVsDeclineRaceHasExactlyOneTerminalWinner() {
        OfferFixture fixture = createOfferFixture(Duration.ofHours(1));
        long auditMarker = latestAuditId();

        ConcurrentRaceHarness.RaceResult<RaceOutcome> race = ConcurrentRaceHarness.race(
                () -> attemptAcceptance(fixture),
                () -> attemptDecline(fixture),
                READY_TIMEOUT,
                FINISH_TIMEOUT);

        List<RaceOutcome> outcomes = race.asList();
        long successfulCalls = outcomes.stream()
                .filter(outcome -> outcome instanceof Accepted || outcome instanceof Declined)
                .count();
        assertEquals(
                1,
                successfulCalls,
                () -> "Accept-versus-decline must have one successful call, but got " + outcomes);
        assertEquals(1, outcomes.stream().filter(ExpectedFailure.class::isInstance).count());

        String finalOfferStatus = slotOfferStatus(fixture.slotOfferId());
        if (SlotOfferStatus.ACCEPTED.name().equals(finalOfferStatus)) {
            assertInstanceOf(Accepted.class, race.first());
            ExpectedFailure declineFailure = assertInstanceOf(ExpectedFailure.class, race.second());
            // Decline has its own generic non-OFFERED conflict exception. Acceptance uses
            // OfferAlreadyResolvedException when it is the side that observes DECLINED.
            assertInstanceOf(SlotOfferNotOfferedException.class, declineFailure.exception());

            Long newAppointmentId = assertAcceptanceState(fixture);
            assertSuccessfulAcceptanceAudits(fixture, newAppointmentId, auditMarker);
        } else if (SlotOfferStatus.DECLINED.name().equals(finalOfferStatus)) {
            ExpectedFailure acceptFailure = assertInstanceOf(ExpectedFailure.class, race.first());
            assertInstanceOf(OfferAlreadyResolvedException.class, acceptFailure.exception());
            assertInstanceOf(Declined.class, race.second());

            assertNoNewAppointment(fixture);
            assertEquals(AppointmentStatus.SCHEDULED.name(), appointmentStatus(
                    fixture.oldAppointmentId()));
            assertEquals("ACTIVE", waitlistEntryStatus(fixture.waitlistEntryId()));
            assertEquals(RecoveryJobStatus.OPEN.name(), recoveryJobStatus(fixture.recoveryJobId()));
            assertEquals(1, auditCountAfter(auditMarker));
            assertEquals(1, auditCount(
                    auditMarker, "SlotOffer", fixture.slotOfferId(), "DECLINE"));
        } else {
            fail("Unexpected final SlotOffer status: " + finalOfferStatus);
        }
    }

    @Test
    void acceptVsAggressiveExpiryRaceCommitsExpiryEvenIfAcceptanceLoses() {
        OfferFixture fixture = createOfferFixture(Duration.ofMillis(120));
        long auditMarker = latestAuditId();

        ConcurrentRaceHarness.RaceResult<RaceOutcome> race = ConcurrentRaceHarness.race(
                () -> {
                    pauseNearExpiryBoundary();
                    return attemptAcceptance(fixture);
                },
                () -> {
                    pauseNearExpiryBoundary();
                    return new ExpiryChecked(
                            slotOfferExpiryOfferProcessor.expireOfferIfEligible(
                                    fixture.slotOfferId()));
                },
                READY_TIMEOUT,
                FINISH_TIMEOUT);

        String finalOfferStatus = slotOfferStatus(fixture.slotOfferId());
        if (SlotOfferStatus.ACCEPTED.name().equals(finalOfferStatus)) {
            assertInstanceOf(Accepted.class, race.first());
            ExpiryChecked expiryResult = assertInstanceOf(ExpiryChecked.class, race.second());
            assertEquals(false, expiryResult.expired());

            Long newAppointmentId = assertAcceptanceState(fixture);
            assertSuccessfulAcceptanceAudits(fixture, newAppointmentId, auditMarker);
        } else if (SlotOfferStatus.EXPIRED.name().equals(finalOfferStatus)) {
            ExpectedFailure acceptFailure = assertInstanceOf(ExpectedFailure.class, race.first());
            assertInstanceOf(OfferExpiredException.class, acceptFailure.exception());
            assertInstanceOf(ExpiryChecked.class, race.second());

            assertNoNewAppointment(fixture);
            assertEquals(AppointmentStatus.SCHEDULED.name(), appointmentStatus(
                    fixture.oldAppointmentId()));
            assertEquals("ACTIVE", waitlistEntryStatus(fixture.waitlistEntryId()));

            // The observed expiry result is OPEN. That matches RM-03's requirement that the
            // "job remains available/not incorrectly marked FILLED": neither expiry path
            // changes RecoveryJob, while only successful acceptance changes it to FILLED.
            assertEquals(RecoveryJobStatus.OPEN.name(), recoveryJobStatus(fixture.recoveryJobId()));
            assertTrue(auditCount(
                    auditMarker, "SlotOffer", fixture.slotOfferId(), "EXPIRE") >= 1);
        } else {
            fail("Unexpected final SlotOffer status: " + finalOfferStatus);
        }
    }

    private RaceOutcome attemptAcceptance(OfferFixture fixture) {
        try {
            Appointment appointment = offerAcceptanceOrchestrator.acceptOffer(
                    fixture.slotOfferId(), fixture.patientId(), fixture.patientId());
            return new Accepted(appointment.getId());
        } catch (AppointmentNotScheduledException
                | OfferAlreadyAcceptedException
                | OfferAlreadyResolvedException
                | OfferExpiredException exception) {
            return new ExpectedFailure(exception);
        }
    }

    private RaceOutcome attemptDecline(OfferFixture fixture) {
        try {
            slotOfferDeclineService.declineOffer(fixture.slotOfferId(), fixture.patientId());
            return new Declined();
        } catch (SlotOfferNotOfferedException exception) {
            return new ExpectedFailure(exception);
        }
    }

    private void pauseNearExpiryBoundary() throws InterruptedException {
        long delay = ThreadLocalRandom.current().nextLong(241);
        TimeUnit.MILLISECONDS.sleep(delay);
    }

    private Long assertAcceptanceState(OfferFixture fixture) {
        List<Long> newAppointmentIds = newAppointmentIds(fixture);
        assertEquals(1, newAppointmentIds.size());
        Long newAppointmentId = newAppointmentIds.getFirst();

        assertEquals(AppointmentStatus.SCHEDULED.name(), appointmentStatus(newAppointmentId));
        assertEquals(fixture.patientId(), jdbcTemplate.queryForObject(
                "SELECT patient_id FROM appointment WHERE id = ?",
                Long.class,
                newAppointmentId));
        assertEquals(AppointmentStatus.CANCELLED.name(), appointmentStatus(
                fixture.oldAppointmentId()));
        assertEquals(CancellationReason.RESCHEDULED.name(), jdbcTemplate.queryForObject(
                "SELECT cancellation_reason FROM appointment WHERE id = ?",
                String.class,
                fixture.oldAppointmentId()));
        assertEquals(newAppointmentId, jdbcTemplate.queryForObject(
                "SELECT replaced_by_appointment_id FROM appointment WHERE id = ?",
                Long.class,
                fixture.oldAppointmentId()));
        assertEquals(SlotOfferStatus.ACCEPTED.name(), slotOfferStatus(fixture.slotOfferId()));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT accepted_at FROM slot_offer WHERE id = ?",
                Timestamp.class,
                fixture.slotOfferId()));
        assertEquals("FULFILLED", waitlistEntryStatus(fixture.waitlistEntryId()));
        assertEquals(RecoveryJobStatus.FILLED.name(), recoveryJobStatus(fixture.recoveryJobId()));
        return newAppointmentId;
    }

    private void assertSuccessfulAcceptanceAudits(
            OfferFixture fixture, Long newAppointmentId, long auditMarker) {
        Long oldIntervalJobId = jdbcTemplate.queryForObject(
                "SELECT id FROM recovery_job WHERE source_appointment_id = ?",
                Long.class,
                fixture.oldAppointmentId());

        assertEquals(6, auditCountAfter(auditMarker));
        assertEquals(1, auditCount(auditMarker, "Appointment", newAppointmentId, "CREATE"));
        assertEquals(1, auditCount(
                auditMarker, "Appointment", fixture.oldAppointmentId(), "CANCEL"));
        assertEquals(1, auditCount(
                auditMarker, "WaitlistEntry", fixture.waitlistEntryId(), "FULFILL"));
        assertEquals(1, auditCount(
                auditMarker, "SlotOffer", fixture.slotOfferId(), "ACCEPT"));
        assertEquals(1, auditCount(
                auditMarker, "RecoveryJob", fixture.recoveryJobId(), "FILLED"));
        assertEquals(1, auditCount(auditMarker, "RecoveryJob", oldIntervalJobId, "CREATE"));
    }

    private void assertNoNewAppointment(OfferFixture fixture) {
        assertTrue(newAppointmentIds(fixture).isEmpty());
    }

    private List<Long> newAppointmentIds(OfferFixture fixture) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM appointment WHERE id NOT IN (?, ?) ORDER BY id",
                Long.class,
                fixture.oldAppointmentId(),
                fixture.sourceAppointmentId());
    }

    private String appointmentStatus(Long appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM appointment WHERE id = ?", String.class, appointmentId);
    }

    private String waitlistEntryStatus(Long waitlistEntryId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM waitlist_entry WHERE id = ?", String.class, waitlistEntryId);
    }

    private String recoveryJobStatus(Long recoveryJobId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM recovery_job WHERE id = ?", String.class, recoveryJobId);
    }

    private String slotOfferStatus(Long slotOfferId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM slot_offer WHERE id = ?", String.class, slotOfferId);
    }

    private long latestAuditId() {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM audit_log", Long.class);
    }

    private int auditCountAfter(long auditMarker) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE id > ?", Integer.class, auditMarker);
    }

    private int auditCount(
            long auditMarker, String entityType, Long entityId, String action) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log "
                        + "WHERE id > ? AND entity_type = ? AND entity_id = ? AND action = ?",
                Integer.class,
                auditMarker,
                entityType,
                entityId,
                action);
    }

    private OfferFixture createOfferFixture(Duration offerLifetime) {
        long number = SEQUENCE.incrementAndGet();
        Long specialtyId = jdbcTemplate.queryForObject(
                "INSERT INTO specialty (name) VALUES (?) RETURNING id",
                Long.class,
                "Race Matrix Specialty " + number);
        Long appointmentTypeId = jdbcTemplate.queryForObject(
                "INSERT INTO appointment_type "
                        + "(name, duration_minutes, specialty_id) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "Race Matrix Type " + number,
                60,
                specialtyId);
        Long patientId = insertUser("race-matrix-patient-" + number, "PATIENT");
        Long oldProviderId = insertProvider(specialtyId, "old-" + number);
        Long offeredProviderId = insertProvider(specialtyId, "offered-" + number);
        Instant oldStart = Instant.parse("2060-01-01T14:00:00Z")
                .plusSeconds(number * 604_800);
        Instant offeredStart = oldStart.plusSeconds(172_800);
        Long oldAppointmentId = insertAppointment(
                patientId,
                oldProviderId,
                appointmentTypeId,
                oldStart,
                AppointmentStatus.SCHEDULED);
        Long sourceAppointmentId = insertAppointment(
                patientId,
                offeredProviderId,
                appointmentTypeId,
                offeredStart,
                AppointmentStatus.CANCELLED);
        Long waitlistEntryId = insertWaitlistEntry(
                patientId, oldAppointmentId, appointmentTypeId);
        Long recoveryJobId = jdbcTemplate.queryForObject(
                "INSERT INTO recovery_job (source_appointment_id, status) "
                        + "VALUES (?, 'OPEN') RETURNING id",
                Long.class,
                sourceAppointmentId);
        Long slotOfferId = jdbcTemplate.queryForObject(
                "INSERT INTO slot_offer "
                        + "(recovery_job_id, waitlist_entry_id, status, expires_at) "
                        + "VALUES (?, ?, 'OFFERED', ?) RETURNING id",
                Long.class,
                recoveryJobId,
                waitlistEntryId,
                Timestamp.from(Instant.now().plus(offerLifetime)));

        return new OfferFixture(
                patientId,
                oldAppointmentId,
                sourceAppointmentId,
                waitlistEntryId,
                recoveryJobId,
                slotOfferId);
    }

    private Long insertAppointment(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Instant startAt,
            AppointmentStatus status) {
        String cancellationReason = status == AppointmentStatus.CANCELLED
                ? CancellationReason.PATIENT_CANCELLED.name()
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
                Timestamp.from(startAt.plusSeconds(3600)),
                status.name(),
                cancellationReason);
    }

    private Long insertWaitlistEntry(
            Long patientId, Long appointmentId, Long appointmentTypeId) {
        LocalDate date = LocalDate.of(2060, 1, 1).plusDays(SEQUENCE.incrementAndGet());
        return jdbcTemplate.queryForObject(
                "INSERT INTO waitlist_entry "
                        + "(patient_id, current_appointment_id, appointment_type_id, "
                        + "earliest_appointment_date, latest_appointment_date, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE') RETURNING id",
                Long.class,
                patientId,
                appointmentId,
                appointmentTypeId,
                Date.valueOf(date),
                Date.valueOf(date.plusDays(7)));
    }

    private Long insertProvider(Long specialtyId, String name) {
        Long providerUserId = insertUser("race-matrix-provider-" + name, "PROVIDER");
        return jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "RACE-MATRIX-" + name);
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

    private sealed interface RaceOutcome
            permits Accepted, Declined, ExpiryChecked, ExpectedFailure {
    }

    private record Accepted(Long appointmentId) implements RaceOutcome {
    }

    private record Declined() implements RaceOutcome {
    }

    private record ExpiryChecked(boolean expired) implements RaceOutcome {
    }

    private record ExpectedFailure(RuntimeException exception) implements RaceOutcome {
    }

    private record OfferFixture(
            Long patientId,
            Long oldAppointmentId,
            Long sourceAppointmentId,
            Long waitlistEntryId,
            Long recoveryJobId,
            Long slotOfferId) {
    }
}

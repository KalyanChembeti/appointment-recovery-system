package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.CancellationReason;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.exception.OfferAlreadyResolvedException;
import com.recoverysystem.exception.PatientDoubleBookedException;
import com.recoverysystem.exception.WaitlistEntryNotActiveException;
import com.recoverysystem.service.OfferAcceptanceOrchestrator;
import com.recoverysystem.service.WaitlistEntryRemovalService;
import com.recoverysystem.support.ConcurrentRaceHarness;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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
class RaceMatrixOfferAcceptanceTest {

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
    private WaitlistEntryRemovalService waitlistEntryRemovalService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearWorkflowData() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_log, slot_offer, recovery_job, "
                + "waitlist_entry, appointment, provider_schedule, provider_unavailability, "
                + "provider, appointment_type, specialty, users RESTART IDENTITY CASCADE");
    }

    @Test
    void siblingRemovalVsAcceptanceConverges() {
        SiblingRemovalFixture fixture = createSiblingRemovalFixture();

        ConcurrentRaceHarness.RaceResult<Object> result = ConcurrentRaceHarness.race(
                () -> offerAcceptanceOrchestrator.acceptOffer(
                        fixture.offerAId(), fixture.patientId(), fixture.acceptanceActorUserId()),
                () -> attemptSiblingRemoval(fixture),
                READY_TIMEOUT,
                FINISH_TIMEOUT);

        Appointment newAppointment = assertInstanceOf(Appointment.class, result.first());
        RemovalOutcome removalOutcome = assertInstanceOf(RemovalOutcome.class, result.second());

        assertEquals(
                WaitlistEntryStatus.FULFILLED.name(),
                textValue("SELECT status FROM waitlist_entry WHERE id = ?", fixture.entryW1Id()));
        assertEquals(
                WaitlistEntryStatus.REMOVED.name(),
                textValue("SELECT status FROM waitlist_entry WHERE id = ?", fixture.entryW2Id()));
        assertEquals(
                AppointmentStatus.CANCELLED.name(),
                textValue("SELECT status FROM appointment WHERE id = ?",
                        fixture.oldAppointmentId()));
        assertEquals(
                CancellationReason.RESCHEDULED.name(),
                textValue("SELECT cancellation_reason FROM appointment WHERE id = ?",
                        fixture.oldAppointmentId()));
        assertEquals(
                AppointmentStatus.SCHEDULED.name(),
                textValue("SELECT status FROM appointment WHERE id = ?", newAppointment.getId()));
        assertEquals(
                1L,
                count("SELECT COUNT(*) FROM appointment "
                                + "WHERE patient_id = ? AND status = 'SCHEDULED'",
                        fixture.patientId()));

        assertEquals(
                SlotOfferStatus.ACCEPTED.name(),
                textValue("SELECT status FROM slot_offer WHERE id = ?", fixture.offerAId()));
        assertEquals(
                SlotOfferStatus.CANCELLED.name(),
                textValue("SELECT status FROM slot_offer WHERE id = ?", fixture.offerBId()));
        assertEquals(
                1L,
                count("SELECT COUNT(*) FROM audit_log "
                                + "WHERE entity_type = 'SlotOffer' AND entity_id = ? "
                                + "AND action = 'CANCEL'",
                        fixture.offerBId()));

        String cancellationReason = textValue(
                "SELECT reason FROM audit_log "
                        + "WHERE entity_type = 'SlotOffer' AND entity_id = ? "
                        + "AND action = 'CANCEL'",
                fixture.offerBId());
        if (removalOutcome instanceof RemovedNormally) {
            assertEquals("ENTRY_REMOVED", cancellationReason);
            System.out.println("RM-12 offer B resolved by: waitlist removal (ENTRY_REMOVED)");
        } else {
            assertInstanceOf(AlreadyInactive.class, removalOutcome);
            assertEquals("SUPERSEDED_BY_ACCEPTANCE", cancellationReason);
            System.out.println(
                    "RM-12 offer B resolved by: acceptance cleanup "
                            + "(SUPERSEDED_BY_ACCEPTANCE); removal observed "
                            + "WaitlistEntryNotActiveException");
        }
    }

    @Test
    void acceptAvsAcceptBHonestOutcomeDistribution() {
        DualAcceptanceFixture fixture = createDualAcceptanceFixture();

        ConcurrentRaceHarness.RaceResult<AcceptanceOutcome> result =
                ConcurrentRaceHarness.race(
                        () -> attemptAcceptance(
                                fixture.offerAId(),
                                fixture.patientId(),
                                fixture.actorAUserId()),
                        () -> attemptAcceptance(
                                fixture.offerBId(),
                                fixture.patientId(),
                                fixture.actorBUserId()),
                        READY_TIMEOUT,
                        FINISH_TIMEOUT);

        List<AcceptanceOutcome> outcomes = result.asList();
        assertEquals(1, outcomes.stream().filter(AcceptanceSuccess.class::isInstance).count());
        assertEquals(1, outcomes.stream().filter(AcceptanceFailure.class::isInstance).count());

        boolean aWon = result.first() instanceof AcceptanceSuccess;
        Long winningOfferId = aWon ? fixture.offerAId() : fixture.offerBId();
        Long losingOfferId = aWon ? fixture.offerBId() : fixture.offerAId();
        AcceptanceSuccess success = outcomes.stream()
                .filter(AcceptanceSuccess.class::isInstance)
                .map(AcceptanceSuccess.class::cast)
                .findFirst()
                .orElseThrow();
        AcceptanceFailure failure = outcomes.stream()
                .filter(AcceptanceFailure.class::isInstance)
                .map(AcceptanceFailure.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(
                SlotOfferStatus.ACCEPTED.name(),
                textValue("SELECT status FROM slot_offer WHERE id = ?", winningOfferId));
        assertEquals(
                SlotOfferStatus.CANCELLED.name(),
                textValue("SELECT status FROM slot_offer WHERE id = ?", losingOfferId));
        assertEquals(
                1L,
                count("SELECT COUNT(*) FROM audit_log "
                                + "WHERE entity_type = 'SlotOffer' AND entity_id = ? "
                                + "AND action = 'CANCEL'",
                        losingOfferId));

        String cancellationReason = textValue(
                "SELECT reason FROM audit_log "
                        + "WHERE entity_type = 'SlotOffer' AND entity_id = ? "
                        + "AND action = 'CANCEL'",
                losingOfferId);
        assertTrue(Set.of("PATIENT_SCHEDULE_CONFLICT", "SUPERSEDED_BY_ACCEPTANCE")
                .contains(cancellationReason));

        List<Long> newAppointmentIds = jdbcTemplate.queryForList(
                "SELECT id FROM appointment WHERE patient_id = ? "
                        + "AND id NOT IN (?, ?, ?, ?) ORDER BY id",
                Long.class,
                fixture.patientId(),
                fixture.oldAppointmentAId(),
                fixture.oldAppointmentBId(),
                fixture.sourceAppointmentAId(),
                fixture.sourceAppointmentBId());
        assertEquals(1, newAppointmentIds.size());
        assertEquals(success.appointmentId(), newAppointmentIds.getFirst());
        assertEquals(
                AppointmentStatus.SCHEDULED.name(),
                textValue("SELECT status FROM appointment WHERE id = ?",
                        newAppointmentIds.getFirst()));
        assertEquals(
                0L,
                count("SELECT COUNT(*) FROM appointment first_appointment "
                                + "JOIN appointment second_appointment "
                                + "ON first_appointment.id < second_appointment.id "
                                + "AND first_appointment.patient_id = second_appointment.patient_id "
                                + "AND first_appointment.start_at < second_appointment.end_at "
                                + "AND first_appointment.end_at > second_appointment.start_at "
                                + "WHERE first_appointment.patient_id = ? "
                                + "AND first_appointment.status = 'SCHEDULED' "
                                + "AND second_appointment.status = 'SCHEDULED'",
                        fixture.patientId()));

        String family;
        if (failure.exceptionType().equals(PatientDoubleBookedException.class)) {
            assertEquals("PATIENT_SCHEDULE_CONFLICT", cancellationReason);
            family = aWon ? "(a) A committed; B lost at patient exclusion"
                    : "(b) B committed; A lost at patient exclusion";
        } else {
            assertEquals(OfferAlreadyResolvedException.class, failure.exceptionType());
            assertEquals("SUPERSEDED_BY_ACCEPTANCE", cancellationReason);
            family = "(c) winner's Category D cleanup cancelled the losing offer";
        }

        System.out.println("RM-04 winner: " + (aWon ? "A" : "B")
                + "; family: " + family
                + "; losing exception: " + failure.exceptionType().getSimpleName()
                + "; losing cancellation reason: " + cancellationReason);
    }

    private RemovalOutcome attemptSiblingRemoval(SiblingRemovalFixture fixture) {
        try {
            WaitlistEntry removed = waitlistEntryRemovalService.removeWaitlistEntry(
                    fixture.entryW2Id(), fixture.removalActorUserId());
            return new RemovedNormally(removed.getId());
        } catch (WaitlistEntryNotActiveException exception) {
            return new AlreadyInactive(exception.getClass());
        }
    }

    private AcceptanceOutcome attemptAcceptance(
            Long offerId, Long patientId, Long actorUserId) {
        try {
            Appointment appointment =
                    offerAcceptanceOrchestrator.acceptOffer(offerId, patientId, actorUserId);
            return new AcceptanceSuccess(appointment.getId());
        } catch (PatientDoubleBookedException | OfferAlreadyResolvedException exception) {
            return new AcceptanceFailure(exception.getClass());
        }
    }

    private SiblingRemovalFixture createSiblingRemovalFixture() {
        long number = SEQUENCE.incrementAndGet();
        Long specialtyId = insertSpecialty("RM-12 Specialty " + number);
        Long appointmentTypeId = insertAppointmentType(
                specialtyId, "RM-12 Appointment Type " + number);
        Long patientId = insertUser("rm12-patient-" + number, "PATIENT");
        Long oldProviderId = insertProvider(specialtyId, "rm12-old-" + number);
        Long offeredProviderAId = insertProvider(specialtyId, "rm12-offered-a-" + number);
        Long offeredProviderBId = insertProvider(specialtyId, "rm12-offered-b-" + number);
        Instant oldStart = Instant.parse("2084-01-08T15:00:00Z")
                .plusSeconds(number * 1_209_600);
        Instant offeredStartA = oldStart.minusSeconds(172_800);
        Instant offeredStartB = oldStart.minusSeconds(86_400);
        Long oldAppointmentId = insertAppointment(
                patientId,
                oldProviderId,
                appointmentTypeId,
                oldStart,
                AppointmentStatus.SCHEDULED);
        Long sourceAppointmentAId = insertAppointment(
                patientId,
                offeredProviderAId,
                appointmentTypeId,
                offeredStartA,
                AppointmentStatus.CANCELLED);
        Long sourceAppointmentBId = insertAppointment(
                patientId,
                offeredProviderBId,
                appointmentTypeId,
                offeredStartB,
                AppointmentStatus.CANCELLED);
        Long entryW1Id = insertWaitlistEntry(patientId, oldAppointmentId, appointmentTypeId);
        Long entryW2Id = insertWaitlistEntry(patientId, oldAppointmentId, appointmentTypeId);
        Long recoveryJobAId = insertRecoveryJob(sourceAppointmentAId);
        Long recoveryJobBId = insertRecoveryJob(sourceAppointmentBId);
        Long offerAId = insertOffer(recoveryJobAId, entryW1Id);
        Long offerBId = insertOffer(recoveryJobBId, entryW2Id);
        Long acceptanceActorUserId = insertUser("rm12-acceptance-actor-" + number, "RECEPTIONIST");
        Long removalActorUserId = insertUser("rm12-removal-actor-" + number, "RECEPTIONIST");

        return new SiblingRemovalFixture(
                patientId,
                oldAppointmentId,
                entryW1Id,
                entryW2Id,
                offerAId,
                offerBId,
                acceptanceActorUserId,
                removalActorUserId);
    }

    private DualAcceptanceFixture createDualAcceptanceFixture() {
        long number = SEQUENCE.incrementAndGet();
        Long specialtyId = insertSpecialty("RM-04 Specialty " + number);
        Long appointmentTypeId = insertAppointmentType(
                specialtyId, "RM-04 Appointment Type " + number);
        Long patientId = insertUser("rm04-patient-" + number, "PATIENT");
        Long oldProviderAId = insertProvider(specialtyId, "rm04-old-a-" + number);
        Long oldProviderBId = insertProvider(specialtyId, "rm04-old-b-" + number);
        Long offeredProviderAId = insertProvider(specialtyId, "rm04-offered-a-" + number);
        Long offeredProviderBId = insertProvider(specialtyId, "rm04-offered-b-" + number);
        Instant oldStartA = Instant.parse("2085-01-01T15:00:00Z")
                .plusSeconds(number * 1_209_600);
        Instant oldStartB = oldStartA.plusSeconds(604_800);
        Instant offeredStartA = oldStartA.plusSeconds(172_800);
        Instant offeredStartB = offeredStartA.plusSeconds(1_800);
        Long oldAppointmentAId = insertAppointment(
                patientId,
                oldProviderAId,
                appointmentTypeId,
                oldStartA,
                AppointmentStatus.SCHEDULED);
        Long oldAppointmentBId = insertAppointment(
                patientId,
                oldProviderBId,
                appointmentTypeId,
                oldStartB,
                AppointmentStatus.SCHEDULED);
        Long sourceAppointmentAId = insertAppointment(
                patientId,
                offeredProviderAId,
                appointmentTypeId,
                offeredStartA,
                AppointmentStatus.CANCELLED);
        Long sourceAppointmentBId = insertAppointment(
                patientId,
                offeredProviderBId,
                appointmentTypeId,
                offeredStartB,
                AppointmentStatus.CANCELLED);
        Long entryAId = insertWaitlistEntry(patientId, oldAppointmentAId, appointmentTypeId);
        Long entryBId = insertWaitlistEntry(patientId, oldAppointmentBId, appointmentTypeId);
        Long recoveryJobAId = insertRecoveryJob(sourceAppointmentAId);
        Long recoveryJobBId = insertRecoveryJob(sourceAppointmentBId);
        Long offerAId = insertOffer(recoveryJobAId, entryAId);
        Long offerBId = insertOffer(recoveryJobBId, entryBId);
        Long actorAUserId = insertUser("rm04-actor-a-" + number, "RECEPTIONIST");
        Long actorBUserId = insertUser("rm04-actor-b-" + number, "RECEPTIONIST");

        return new DualAcceptanceFixture(
                patientId,
                oldAppointmentAId,
                oldAppointmentBId,
                sourceAppointmentAId,
                sourceAppointmentBId,
                offerAId,
                offerBId,
                actorAUserId,
                actorBUserId);
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
        Long providerUserId = insertUser(name, "PROVIDER");
        return jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "RACE-OFFER-" + SEQUENCE.incrementAndGet());
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
                Timestamp.from(startAt.plusSeconds(3_600)),
                status.name(),
                cancellationReason);
    }

    private Long insertWaitlistEntry(
            Long patientId, Long appointmentId, Long appointmentTypeId) {
        LocalDate startDate = LocalDate.of(2080, 1, 1).plusDays(SEQUENCE.incrementAndGet());
        return jdbcTemplate.queryForObject(
                "INSERT INTO waitlist_entry "
                        + "(patient_id, current_appointment_id, appointment_type_id, "
                        + "earliest_appointment_date, latest_appointment_date, "
                        + "preferred_time_of_day, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ANY', 'ACTIVE') RETURNING id",
                Long.class,
                patientId,
                appointmentId,
                appointmentTypeId,
                Date.valueOf(startDate),
                Date.valueOf(startDate.plusDays(30)));
    }

    private Long insertRecoveryJob(Long sourceAppointmentId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO recovery_job (source_appointment_id, status) "
                        + "VALUES (?, 'OPEN') RETURNING id",
                Long.class,
                sourceAppointmentId);
    }

    private Long insertOffer(Long recoveryJobId, Long waitlistEntryId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO slot_offer "
                        + "(recovery_job_id, waitlist_entry_id, status, expires_at) "
                        + "VALUES (?, ?, 'OFFERED', ?) RETURNING id",
                Long.class,
                recoveryJobId,
                waitlistEntryId,
                Timestamp.from(Instant.now().plusSeconds(3_600)));
    }

    private long count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Long.class, arguments);
    }

    private String textValue(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, String.class, arguments);
    }

    private sealed interface RemovalOutcome permits RemovedNormally, AlreadyInactive {
    }

    private record RemovedNormally(Long waitlistEntryId) implements RemovalOutcome {
    }

    private record AlreadyInactive(
            Class<? extends RuntimeException> exceptionType) implements RemovalOutcome {
    }

    private sealed interface AcceptanceOutcome permits AcceptanceSuccess, AcceptanceFailure {
    }

    private record AcceptanceSuccess(Long appointmentId) implements AcceptanceOutcome {
    }

    private record AcceptanceFailure(
            Class<? extends RuntimeException> exceptionType) implements AcceptanceOutcome {
    }

    private record SiblingRemovalFixture(
            Long patientId,
            Long oldAppointmentId,
            Long entryW1Id,
            Long entryW2Id,
            Long offerAId,
            Long offerBId,
            Long acceptanceActorUserId,
            Long removalActorUserId) {
    }

    private record DualAcceptanceFixture(
            Long patientId,
            Long oldAppointmentAId,
            Long oldAppointmentBId,
            Long sourceAppointmentAId,
            Long sourceAppointmentBId,
            Long offerAId,
            Long offerBId,
            Long actorAUserId,
            Long actorBUserId) {
    }
}

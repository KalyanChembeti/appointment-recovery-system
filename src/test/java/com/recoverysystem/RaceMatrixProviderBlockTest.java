package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.ProviderSchedule;
import com.recoverysystem.domain.entity.ProviderUnavailability;
import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;
import com.recoverysystem.domain.enums.RecoveryWorkerOutcome;
import com.recoverysystem.exception.ProviderUnavailableException;
import com.recoverysystem.repository.ProviderScheduleRepository;
import com.recoverysystem.service.DirectBookingService;
import com.recoverysystem.service.ProviderBlockActivationService;
import com.recoverysystem.service.ProviderBlockCreationService;
import com.recoverysystem.service.RecoveryWorkerService;
import com.recoverysystem.support.ConcurrentRaceHarness;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
class RaceMatrixProviderBlockTest {

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
    private ProviderBlockActivationService providerBlockActivationService;

    @Autowired
    private ProviderBlockCreationService providerBlockCreationService;

    @Autowired
    private RecoveryWorkerService recoveryWorkerService;

    @Autowired
    private DirectBookingService directBookingService;

    @Autowired
    private ProviderScheduleRepository providerScheduleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearWorkflowData() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_log, slot_offer, recovery_job, "
                + "waitlist_entry, appointment, provider_schedule, provider_unavailability, "
                + "provider, appointment_type, specialty, users RESTART IDENTITY CASCADE");
        jdbcTemplate.update("UPDATE scheduling_policy SET "
                + "minimum_recovery_lead_minutes = 30, offer_duration_minutes = 10");
    }

    @Test
    void blockActivationVsRecoveryWorkerConverges() {
        RecoveryBlockFixture fixture = createRecoveryBlockFixture();

        ConcurrentRaceHarness.RaceResult<Object> result = ConcurrentRaceHarness.race(
                () -> providerBlockActivationService.activateProviderBlock(
                        fixture.blockId(), fixture.actorUserId()),
                recoveryWorkerService::attemptRecovery,
                READY_TIMEOUT,
                FINISH_TIMEOUT);

        assertInstanceOf(ProviderUnavailability.class, result.first());
        RecoveryWorkerOutcome workerOutcome =
                assertInstanceOf(RecoveryWorkerOutcome.class, result.second());
        System.out.println("RM-09 worker outcome: " + workerOutcome);

        assertEquals(
                ProviderUnavailabilityStatus.ACTIVE.name(),
                textValue("SELECT status FROM provider_unavailability WHERE id = ?",
                        fixture.blockId()));
        assertEquals(
                "SUPPRESSED",
                textValue("SELECT status FROM recovery_job WHERE id = ?",
                        fixture.recoveryJobId()));
        assertEquals(
                "PROVIDER_BLOCK_ACTIVATED",
                textValue("SELECT suppression_reason FROM recovery_job WHERE id = ?",
                        fixture.recoveryJobId()));
        assertEquals(
                0L,
                count("SELECT COUNT(*) FROM slot_offer WHERE recovery_job_id = ?",
                        fixture.recoveryJobId()));
        assertEquals(
                1L,
                auditCount("ProviderUnavailability", fixture.blockId(), "ACTIVATE"));
        assertEquals(
                1L,
                auditCount("RecoveryJob", fixture.recoveryJobId(), "SUPPRESS"));
    }

    @Test
    void bookingVsBlockCreationCannotBothCommitInconsistently() {
        BookingBlockFixture fixture = createBookingBlockFixture();

        ConcurrentRaceHarness.RaceResult<Object> result = ConcurrentRaceHarness.race(
                () -> attemptBooking(fixture),
                () -> providerBlockCreationService.createProviderBlock(
                        fixture.providerId(),
                        fixture.startAt(),
                        fixture.endAt(),
                        "Race matrix provider block",
                        fixture.actorUserId()),
                READY_TIMEOUT,
                FINISH_TIMEOUT);

        ProviderUnavailability createdBlock =
                assertInstanceOf(ProviderUnavailability.class, result.second());
        ProviderUnavailabilityStatus savedStatus = ProviderUnavailabilityStatus.valueOf(
                textValue(
                        "SELECT status FROM provider_unavailability WHERE id = ?",
                        createdBlock.getId()));
        long appointmentCount = count(
                "SELECT COUNT(*) FROM appointment "
                        + "WHERE provider_id = ? AND start_at = ? AND end_at = ? "
                        + "AND status = 'SCHEDULED'",
                fixture.providerId(),
                Timestamp.from(fixture.startAt()),
                Timestamp.from(fixture.endAt()));

        assertEquals(
                1L,
                count("SELECT COUNT(*) FROM provider_unavailability "
                                + "WHERE provider_id = ? AND start_at = ? AND end_at = ?",
                        fixture.providerId(),
                        Timestamp.from(fixture.startAt()),
                        Timestamp.from(fixture.endAt())));

        // This race must not leave a scheduled appointment together with an ACTIVE block
        // over the same interval, regardless of which transaction gets the Provider lock first.
        if (savedStatus == ProviderUnavailabilityStatus.PENDING) {
            Appointment appointment = assertInstanceOf(Appointment.class, result.first());
            assertEquals(1L, appointmentCount);
            assertEquals(
                    "SCHEDULED",
                    textValue("SELECT status FROM appointment WHERE id = ?",
                            appointment.getId()));
            System.out.println("RM-10 winner: booking; block status: PENDING");
        } else {
            assertEquals(ProviderUnavailabilityStatus.ACTIVE, savedStatus);
            assertInstanceOf(ProviderUnavailableException.class, result.first());
            assertEquals(0L, appointmentCount);
            System.out.println("RM-10 winner: block creation; block status: ACTIVE");
        }
    }

    @Test
    void bookingWinsWhenBlockCreationIsDelayed() {
        BookingBlockFixture fixture = createBookingBlockFixture();

        ConcurrentRaceHarness.RaceResult<Object> result = ConcurrentRaceHarness.race(
                () -> attemptBooking(fixture),
                () -> {
                    Thread.sleep(2000);
                    return providerBlockCreationService.createProviderBlock(
                            fixture.providerId(),
                            fixture.startAt(),
                            fixture.endAt(),
                            "Race matrix provider block",
                            fixture.actorUserId());
                },
                READY_TIMEOUT,
                FINISH_TIMEOUT);

        ProviderUnavailability createdBlock =
                assertInstanceOf(ProviderUnavailability.class, result.second());
        ProviderUnavailabilityStatus savedStatus = ProviderUnavailabilityStatus.valueOf(
                textValue(
                        "SELECT status FROM provider_unavailability WHERE id = ?",
                        createdBlock.getId()));
        long appointmentCount = count(
                "SELECT COUNT(*) FROM appointment "
                        + "WHERE provider_id = ? AND start_at = ? AND end_at = ? "
                        + "AND status = 'SCHEDULED'",
                fixture.providerId(),
                Timestamp.from(fixture.startAt()),
                Timestamp.from(fixture.endAt()));

        assertEquals(ProviderUnavailabilityStatus.PENDING, savedStatus);
        Appointment appointment = assertInstanceOf(Appointment.class, result.first());
        assertEquals(1L, appointmentCount);
        assertEquals(
                "SCHEDULED",
                textValue("SELECT status FROM appointment WHERE id = ?", appointment.getId()));
        System.out.println("RM-10 forced winner: booking; block status: PENDING");
    }

    private Object attemptBooking(BookingBlockFixture fixture) {
        try {
            return directBookingService.bookAppointment(
                    fixture.patientId(),
                    fixture.providerId(),
                    fixture.appointmentTypeId(),
                    fixture.startAt(),
                    fixture.actorUserId());
        } catch (ProviderUnavailableException exception) {
            return exception;
        }
    }

    private RecoveryBlockFixture createRecoveryBlockFixture() {
        long number = SEQUENCE.incrementAndGet();
        LocalDate releaseDate = LocalDate.of(2075, 1, 15).plusDays(number);
        Instant releasedStart = atClinicTime(releaseDate, 12, 0);
        Instant releasedEnd = releasedStart.plusSeconds(3600);
        Long specialtyId = insertSpecialty("RM-09 Specialty " + number);
        Long appointmentTypeId = insertAppointmentType(
                specialtyId, "RM-09 Appointment Type " + number);
        Long releasedProviderId = insertProvider(specialtyId, "RM-09 Released Provider " + number);
        Long sourcePatientId = insertUser("rm09-source-patient-" + number, "PATIENT");
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

        Long candidatePatientId = insertUser("rm09-candidate-patient-" + number, "PATIENT");
        Long anchorProviderId = insertProvider(specialtyId, "RM-09 Anchor Provider " + number);
        Instant anchorStart = releasedEnd.plusSeconds(86_400);
        Long anchorAppointmentId = insertAppointment(
                candidatePatientId,
                anchorProviderId,
                appointmentTypeId,
                anchorStart,
                anchorStart.plusSeconds(3600),
                "SCHEDULED");
        insertWaitlistEntry(
                candidatePatientId,
                anchorAppointmentId,
                appointmentTypeId,
                releaseDate.minusDays(7),
                releaseDate.plusDays(7));

        Long blockId = jdbcTemplate.queryForObject(
                "INSERT INTO provider_unavailability "
                        + "(provider_id, start_at, end_at, status, reason) "
                        + "VALUES (?, ?, ?, 'PENDING', ?) RETURNING id",
                Long.class,
                releasedProviderId,
                Timestamp.from(releasedStart),
                Timestamp.from(releasedEnd),
                "RM-09 pending block");
        Long actorUserId = insertUser("rm09-receptionist-" + number, "RECEPTIONIST");

        return new RecoveryBlockFixture(blockId, recoveryJobId, actorUserId);
    }

    private BookingBlockFixture createBookingBlockFixture() {
        long number = SEQUENCE.incrementAndGet();
        LocalDate bookingDate = LocalDate.of(2076, 2, 12).plusDays(number);
        Instant startAt = atClinicTime(bookingDate, 12, 0);
        Instant endAt = startAt.plusSeconds(3600);
        Long specialtyId = insertSpecialty("RM-10 Specialty " + number);
        Long appointmentTypeId = insertAppointmentType(
                specialtyId, "RM-10 Appointment Type " + number);
        Long providerId = insertProvider(specialtyId, "RM-10 Provider " + number);
        ProviderSchedule schedule = new ProviderSchedule();
        schedule.setProviderId(providerId);
        schedule.setDayOfWeek(bookingDate.getDayOfWeek());
        schedule.setStartTime(LocalTime.of(9, 0));
        schedule.setEndTime(LocalTime.of(17, 0));
        schedule.setActive(true);
        providerScheduleRepository.saveAndFlush(schedule);
        Long patientId = insertUser("rm10-patient-" + number, "PATIENT");
        Long actorUserId = insertUser("rm10-receptionist-" + number, "RECEPTIONIST");
        return new BookingBlockFixture(
                patientId, providerId, appointmentTypeId, actorUserId, startAt, endAt);
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
        Long providerUserId = insertUser(name.toLowerCase().replace(' ', '-'), "PROVIDER");
        return jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "RACE-MATRIX-" + SEQUENCE.incrementAndGet());
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

    private void insertWaitlistEntry(
            Long patientId,
            Long appointmentId,
            Long appointmentTypeId,
            LocalDate earliestDate,
            LocalDate latestDate) {
        jdbcTemplate.update(
                "INSERT INTO waitlist_entry "
                        + "(patient_id, current_appointment_id, appointment_type_id, "
                        + "earliest_appointment_date, latest_appointment_date, "
                        + "preferred_time_of_day, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ANY', 'ACTIVE')",
                patientId,
                appointmentId,
                appointmentTypeId,
                Date.valueOf(earliestDate),
                Date.valueOf(latestDate));
    }

    private long auditCount(String entityType, Long entityId, String action) {
        return count(
                "SELECT COUNT(*) FROM audit_log "
                        + "WHERE entity_type = ? AND entity_id = ? AND action = ?",
                entityType,
                entityId,
                action);
    }

    private long count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Long.class, arguments);
    }

    private String textValue(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, String.class, arguments);
    }

    private static Instant atClinicTime(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(CLINIC_TIME_ZONE).toInstant();
    }

    private record RecoveryBlockFixture(Long blockId, Long recoveryJobId, Long actorUserId) {
    }

    private record BookingBlockFixture(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Long actorUserId,
            Instant startAt,
            Instant endAt) {
    }
}

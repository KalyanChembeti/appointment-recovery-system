package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.ProviderSchedule;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.CancellationReason;
import com.recoverysystem.exception.AppointmentNotScheduledException;
import com.recoverysystem.exception.PatientDoubleBookedException;
import com.recoverysystem.repository.ProviderScheduleRepository;
import com.recoverysystem.service.AppointmentCancellationService;
import com.recoverysystem.service.AppointmentReschedulingService;
import com.recoverysystem.service.DirectBookingService;
import com.recoverysystem.support.ConcurrentRaceHarness;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
class RaceMatrixAppointmentLockTest {

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
    private AppointmentCancellationService appointmentCancellationService;

    @Autowired
    private AppointmentReschedulingService appointmentReschedulingService;

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
    }

    @Test
    void cancellationVsRescheduleConvergesOnCancelledWinner() {
        CancellationRescheduleFixture fixture = createCancellationRescheduleFixture();

        ConcurrentRaceHarness.RaceResult<Object> result = ConcurrentRaceHarness.race(
                () -> attemptCancellation(fixture),
                () -> attemptReschedule(fixture),
                READY_TIMEOUT,
                FINISH_TIMEOUT);

        OriginalAppointmentState original = originalAppointmentState(fixture.appointmentId());
        assertEquals(AppointmentStatus.CANCELLED.name(), original.status());
        assertTrue(Set.of(
                        CancellationReason.STAFF_CANCELLED.name(),
                        CancellationReason.RESCHEDULED.name())
                .contains(original.cancellationReason()));
        assertEquals(
                1L,
                count("SELECT COUNT(*) FROM audit_log "
                                + "WHERE entity_type = 'Appointment' AND entity_id = ? "
                                + "AND action = 'CANCEL'",
                        fixture.appointmentId()));

        if (CancellationReason.RESCHEDULED.name().equals(original.cancellationReason())) {
            assertInstanceOf(AppointmentNotScheduledException.class, result.first());
            Appointment replacement = assertInstanceOf(Appointment.class, result.second());
            assertNotNull(original.replacedByAppointmentId());
            assertEquals(replacement.getId(), original.replacedByAppointmentId());
            assertEquals(
                    2L,
                    count("SELECT COUNT(*) FROM appointment WHERE patient_id = ?",
                            fixture.patientId()));
            assertEquals(
                    AppointmentStatus.SCHEDULED.name(),
                    textValue("SELECT status FROM appointment WHERE id = ?", replacement.getId()));
            assertEquals(
                    fixture.newProviderId(),
                    longValue("SELECT provider_id FROM appointment WHERE id = ?",
                            replacement.getId()));
            assertEquals(
                    fixture.newStartAt(),
                    instantValue("SELECT start_at FROM appointment WHERE id = ?",
                            replacement.getId()));
            System.out.println("RM-05 winner: reschedule");
        } else {
            Appointment cancelled = assertInstanceOf(Appointment.class, result.first());
            assertEquals(fixture.appointmentId(), cancelled.getId());
            assertInstanceOf(AppointmentNotScheduledException.class, result.second());
            assertNull(original.replacedByAppointmentId());
            assertEquals(
                    1L,
                    count("SELECT COUNT(*) FROM appointment WHERE patient_id = ?",
                            fixture.patientId()));
            System.out.println("RM-05 winner: cancellation");
        }
    }

    @Test
    void patientDoubleBookingRaceHasExactlyOneWinner() {
        PatientBookingFixture fixture = createPatientBookingFixture();

        ConcurrentRaceHarness.RaceResult<Object> result = ConcurrentRaceHarness.race(
                () -> attemptBooking(fixture, fixture.firstProviderId()),
                () -> attemptBooking(fixture, fixture.secondProviderId()),
                READY_TIMEOUT,
                FINISH_TIMEOUT);

        List<Object> outcomes = result.asList();
        assertEquals(1, outcomes.stream().filter(Appointment.class::isInstance).count());
        assertEquals(
                1,
                outcomes.stream().filter(PatientDoubleBookedException.class::isInstance).count());
        outcomes.forEach(outcome -> assertTrue(
                outcome instanceof Appointment || outcome instanceof PatientDoubleBookedException,
                () -> "Unexpected concurrent patient-booking outcome: " + outcome));

        assertEquals(
                1L,
                count("SELECT COUNT(*) FROM appointment "
                                + "WHERE patient_id = ? AND status = 'SCHEDULED'",
                        fixture.patientId()));
        assertEquals(
                1L,
                count("SELECT COUNT(*) FROM appointment WHERE patient_id = ?",
                        fixture.patientId()));
        Long winningProviderId = longValue(
                "SELECT provider_id FROM appointment WHERE patient_id = ?",
                fixture.patientId());
        assertTrue(Set.of(fixture.firstProviderId(), fixture.secondProviderId())
                .contains(winningProviderId));

        // This proves the patient-exclusion constraint serializes two genuinely concurrent,
        // previously-nonexistent booking attempts for one patient, unlike the original scenario
        // text's trivial pre-existing-conflict framing.
        System.out.println("RM-07 winning provider: " + winningProviderId);
    }

    private Object attemptCancellation(CancellationRescheduleFixture fixture) {
        try {
            return appointmentCancellationService.cancelAppointment(
                    fixture.appointmentId(),
                    CancellationReason.STAFF_CANCELLED,
                    fixture.actorUserId(),
                    null);
        } catch (AppointmentNotScheduledException exception) {
            return exception;
        }
    }

    private Object attemptReschedule(CancellationRescheduleFixture fixture) {
        try {
            return appointmentReschedulingService.rescheduleAppointment(
                    fixture.appointmentId(),
                    fixture.newProviderId(),
                    fixture.appointmentTypeId(),
                    fixture.newStartAt(),
                    fixture.actorUserId());
        } catch (AppointmentNotScheduledException exception) {
            return exception;
        }
    }

    private Object attemptBooking(PatientBookingFixture fixture, Long providerId) {
        try {
            return directBookingService.bookAppointment(
                    fixture.patientId(),
                    providerId,
                    fixture.appointmentTypeId(),
                    fixture.startAt(),
                    fixture.actorUserId());
        } catch (PatientDoubleBookedException exception) {
            return exception;
        }
    }

    private CancellationRescheduleFixture createCancellationRescheduleFixture() {
        long number = SEQUENCE.incrementAndGet();
        LocalDate originalDate = LocalDate.of(2080, 1, 10).plusDays(number);
        LocalDate replacementDate = originalDate.plusDays(7);
        Instant originalStartAt = atClinicTime(originalDate, 10, 0);
        Instant replacementStartAt = atClinicTime(replacementDate, 11, 0);
        Long specialtyId = insertSpecialty("RM-05 Specialty " + number);
        Long appointmentTypeId = insertAppointmentType(
                specialtyId, "RM-05 Appointment Type " + number);
        Long originalProviderId = insertProvider(specialtyId, "rm05-original-" + number);
        Long newProviderId = insertProvider(specialtyId, "rm05-new-" + number);
        saveProviderSchedule(newProviderId, replacementDate);
        Long patientId = insertUser("rm05-patient-" + number, "PATIENT");
        Long actorUserId = insertUser("rm05-receptionist-" + number, "RECEPTIONIST");
        Long appointmentId = insertAppointment(
                patientId,
                originalProviderId,
                appointmentTypeId,
                originalStartAt,
                originalStartAt.plusSeconds(3600));

        return new CancellationRescheduleFixture(
                appointmentId,
                patientId,
                newProviderId,
                appointmentTypeId,
                replacementStartAt,
                actorUserId);
    }

    private PatientBookingFixture createPatientBookingFixture() {
        long number = SEQUENCE.incrementAndGet();
        LocalDate bookingDate = LocalDate.of(2081, 2, 12).plusDays(number);
        Instant startAt = atClinicTime(bookingDate, 12, 0);
        Long specialtyId = insertSpecialty("RM-07 Specialty " + number);
        Long appointmentTypeId = insertAppointmentType(
                specialtyId, "RM-07 Appointment Type " + number);
        Long firstProviderId = insertProvider(specialtyId, "rm07-first-" + number);
        Long secondProviderId = insertProvider(specialtyId, "rm07-second-" + number);
        saveProviderSchedule(firstProviderId, bookingDate);
        saveProviderSchedule(secondProviderId, bookingDate);
        Long patientId = insertUser("rm07-patient-" + number, "PATIENT");
        Long actorUserId = insertUser("rm07-receptionist-" + number, "RECEPTIONIST");

        return new PatientBookingFixture(
                patientId,
                firstProviderId,
                secondProviderId,
                appointmentTypeId,
                startAt,
                actorUserId);
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
                "RACE-APPOINTMENT-" + SEQUENCE.incrementAndGet());
    }

    private void saveProviderSchedule(Long providerId, LocalDate scheduleDate) {
        providerScheduleRepository.saveAndFlush(new ProviderSchedule(
                null,
                providerId,
                scheduleDate.getDayOfWeek(),
                LocalTime.of(8, 0),
                LocalTime.of(18, 0),
                true));
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
            Instant endAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO appointment "
                        + "(patient_id, provider_id, appointment_type_id, start_at, end_at, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'SCHEDULED') RETURNING id",
                Long.class,
                patientId,
                providerId,
                appointmentTypeId,
                Timestamp.from(startAt),
                Timestamp.from(endAt));
    }

    private OriginalAppointmentState originalAppointmentState(Long appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status, cancellation_reason, replaced_by_appointment_id "
                        + "FROM appointment WHERE id = ?",
                (resultSet, rowNumber) -> new OriginalAppointmentState(
                        resultSet.getString("status"),
                        resultSet.getString("cancellation_reason"),
                        resultSet.getObject("replaced_by_appointment_id", Long.class)),
                appointmentId);
    }

    private long count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Long.class, arguments);
    }

    private Long longValue(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Long.class, arguments);
    }

    private String textValue(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, String.class, arguments);
    }

    private Instant instantValue(String sql, Object... arguments) {
        Timestamp timestamp = jdbcTemplate.queryForObject(sql, Timestamp.class, arguments);
        return timestamp.toInstant();
    }

    private static Instant atClinicTime(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(CLINIC_TIME_ZONE).toInstant();
    }

    private record OriginalAppointmentState(
            String status, String cancellationReason, Long replacedByAppointmentId) {
    }

    private record CancellationRescheduleFixture(
            Long appointmentId,
            Long patientId,
            Long newProviderId,
            Long appointmentTypeId,
            Instant newStartAt,
            Long actorUserId) {
    }

    private record PatientBookingFixture(
            Long patientId,
            Long firstProviderId,
            Long secondProviderId,
            Long appointmentTypeId,
            Instant startAt,
            Long actorUserId) {
    }
}

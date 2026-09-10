package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.ProviderSchedule;
import com.recoverysystem.domain.entity.ProviderUnavailability;
import com.recoverysystem.domain.entity.Specialty;
import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.exception.AppointmentTypeNotFoundException;
import com.recoverysystem.exception.AppointmentTypeSpecialtyMismatchException;
import com.recoverysystem.exception.PatientDoubleBookedException;
import com.recoverysystem.exception.ProviderDoubleBookedException;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.exception.ProviderUnavailableException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderScheduleRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.service.DirectBookingService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class DirectBookingServiceTest {

    private static final ZoneId CLINIC_TIME_ZONE = ZoneId.of("America/New_York");
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
    private DirectBookingService directBookingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SpecialtyRepository specialtyRepository;

    @Autowired
    private AppointmentTypeRepository appointmentTypeRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private ProviderScheduleRepository providerScheduleRepository;

    @Autowired
    private ProviderUnavailabilityRepository providerUnavailabilityRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void validBookingCreatesScheduledAppointmentAndAuditLog() {
        LocalDate date = LocalDate.of(2032, 1, 15);
        BookingFixture fixture = createFixture(date);
        Instant startAt = atClinicTime(date, 12, 0);
        Instant endAt = atClinicTime(date, 13, 0);

        assertEquals(
                providerRepository.findById(fixture.providerId()).orElseThrow().getSpecialtyId(),
                appointmentTypeRepository.findById(fixture.appointmentTypeId())
                        .orElseThrow()
                        .getSpecialtyId());

        Appointment appointment = directBookingService.bookAppointment(
                fixture.firstPatientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                startAt,
                fixture.firstPatientId());

        assertNotNull(appointment.getId());
        assertEquals(AppointmentStatus.SCHEDULED, appointment.getStatus());
        assertEquals(startAt, appointment.getStartAt());
        assertEquals(endAt, appointment.getEndAt());

        List<AuditLog> matchingAuditLogs = auditLogRepository.findAll().stream()
                .filter(log -> "Appointment".equals(log.getEntityType()))
                .filter(log -> appointment.getId().equals(log.getEntityId()))
                .filter(log -> "CREATE".equals(log.getAction()))
                .toList();
        assertEquals(1, matchingAuditLogs.size());
        AuditLog auditLog = matchingAuditLogs.getFirst();
        assertEquals(ActorType.USER, auditLog.getActorType());
        assertEquals(fixture.firstPatientId(), auditLog.getActorUserId());
    }

    @Test
    void mismatchedAppointmentTypeAndProviderSpecialtiesAreRejected() {
        LocalDate date = LocalDate.of(2032, 11, 11);
        BookingFixture fixture = createFixture(date);

        Specialty otherSpecialty = new Specialty();
        otherSpecialty.setName(uniqueValue("Other Specialty"));
        Specialty savedOtherSpecialty = specialtyRepository.saveAndFlush(otherSpecialty);

        AppointmentType mismatchedAppointmentType = new AppointmentType();
        mismatchedAppointmentType.setName(uniqueValue("Mismatched Appointment Type"));
        mismatchedAppointmentType.setDurationMinutes(60);
        mismatchedAppointmentType.setSpecialtyId(savedOtherSpecialty.getId());
        mismatchedAppointmentType.setActive(true);
        AppointmentType savedMismatchedAppointmentType =
                appointmentTypeRepository.saveAndFlush(mismatchedAppointmentType);

        assertThrows(
                AppointmentTypeSpecialtyMismatchException.class,
                () -> directBookingService.bookAppointment(
                        fixture.firstPatientId(),
                        fixture.providerId(),
                        savedMismatchedAppointmentType.getId(),
                        atClinicTime(date, 12, 0),
                        null));

        assertEquals(0, appointmentCountForProvider(fixture.providerId()));
    }

    @Test
    void nonexistentAppointmentTypeIsRejected() {
        LocalDate date = LocalDate.of(2032, 12, 9);
        BookingFixture fixture = createFixture(date);

        assertThrows(
                AppointmentTypeNotFoundException.class,
                () -> directBookingService.bookAppointment(
                        fixture.firstPatientId(),
                        fixture.providerId(),
                        Long.MAX_VALUE,
                        atClinicTime(date, 12, 0),
                        null));

        assertEquals(0, appointmentCountForProvider(fixture.providerId()));
    }

    @Test
    void overlappingProviderBookingThrowsProviderDoubleBookedAndRollsBack() {
        LocalDate date = LocalDate.of(2032, 2, 12);
        BookingFixture fixture = createFixture(date);
        Instant firstStart = atClinicTime(date, 12, 0);

        directBookingService.bookAppointment(
                fixture.firstPatientId(), fixture.providerId(), fixture.appointmentTypeId(),
                firstStart, null);

        assertThrows(ProviderDoubleBookedException.class, () -> directBookingService.bookAppointment(
                fixture.secondPatientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                atClinicTime(date, 12, 30),
                null));

        assertEquals(1, appointmentCountForProvider(fixture.providerId()));
    }

    @Test
    void overlappingPatientBookingThrowsPatientDoubleBooked() {
        LocalDate date = LocalDate.of(2032, 3, 11);
        BookingFixture fixture = createFixture(date);
        Provider secondProvider = saveProviderWithSchedule(fixture.specialtyId(), date);

        directBookingService.bookAppointment(
                fixture.firstPatientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                atClinicTime(date, 12, 0),
                null);

        assertThrows(PatientDoubleBookedException.class, () -> directBookingService.bookAppointment(
                fixture.firstPatientId(),
                secondProvider.getId(),
                fixture.appointmentTypeId(),
                atClinicTime(date, 12, 30),
                null));
    }

    @Test
    void adjacentProviderBookingsBothSucceed() {
        LocalDate date = LocalDate.of(2032, 4, 15);
        BookingFixture fixture = createFixture(date);

        Appointment first = directBookingService.bookAppointment(
                fixture.firstPatientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                atClinicTime(date, 12, 0),
                null);
        Appointment second = directBookingService.bookAppointment(
                fixture.secondPatientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                atClinicTime(date, 13, 0),
                null);

        assertNotNull(first.getId());
        assertNotNull(second.getId());
        assertEquals(2, appointmentCountForProvider(fixture.providerId()));
    }

    @Test
    void activeProviderUnavailabilityRejectsBooking() {
        assertBlockingUnavailabilityRejectsBooking(ProviderUnavailabilityStatus.ACTIVE);
    }

    @Test
    void pendingProviderUnavailabilityAlsoRejectsBooking() {
        assertBlockingUnavailabilityRejectsBooking(ProviderUnavailabilityStatus.PENDING);
    }

    @Test
    void cancelledProviderUnavailabilityDoesNotRejectBooking() {
        LocalDate date = LocalDate.of(2032, 7, 15);
        BookingFixture fixture = createFixture(date);
        saveUnavailability(fixture.providerId(), date, ProviderUnavailabilityStatus.CANCELLED);

        Appointment appointment = directBookingService.bookAppointment(
                fixture.firstPatientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                atClinicTime(date, 12, 0),
                null);

        assertNotNull(appointment.getId());
        assertEquals(AppointmentStatus.SCHEDULED, appointment.getStatus());
    }

    @Test
    void bookingOutsideProviderWorkingHoursIsRejected() {
        LocalDate date = LocalDate.of(2032, 8, 12);
        BookingFixture fixture = createFixture(date);

        assertThrows(ProviderUnavailableException.class, () -> directBookingService.bookAppointment(
                fixture.firstPatientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                atClinicTime(date, 8, 0),
                null));

        assertEquals(0, appointmentCountForProvider(fixture.providerId()));
    }

    @Test
    void nonexistentProviderIsRejected() {
        LocalDate date = LocalDate.of(2032, 9, 16);
        BookingFixture fixture = createFixture(date);

        assertThrows(ProviderNotFoundException.class, () -> directBookingService.bookAppointment(
                fixture.firstPatientId(),
                Long.MAX_VALUE,
                fixture.appointmentTypeId(),
                atClinicTime(date, 12, 0),
                null));
    }

    @Test
    void concurrentOverlappingProviderBookingsProduceOneWinner() throws Exception {
        LocalDate date = LocalDate.of(2032, 10, 14);
        BookingFixture fixture = createFixture(date);
        Instant startAt = atClinicTime(date, 12, 0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Object> firstAttempt = executor.submit(() -> attemptBookingAfterSignal(
                    fixture.firstPatientId(), fixture, startAt, ready, start));
            Future<Object> secondAttempt = executor.submit(() -> attemptBookingAfterSignal(
                    fixture.secondPatientId(), fixture, startAt, ready, start));

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Booking threads did not become ready");
            start.countDown();

            List<Object> outcomes = List.of(
                    firstAttempt.get(20, TimeUnit.SECONDS),
                    secondAttempt.get(20, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(Appointment.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter(ProviderDoubleBookedException.class::isInstance).count());
            outcomes.forEach(outcome -> assertTrue(
                    outcome instanceof Appointment || outcome instanceof ProviderDoubleBookedException,
                    () -> "Unexpected concurrent booking outcome: " + outcome));
            assertEquals(1, appointmentCountForProvider(fixture.providerId()));
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertBlockingUnavailabilityRejectsBooking(ProviderUnavailabilityStatus status) {
        LocalDate date = status == ProviderUnavailabilityStatus.ACTIVE
                ? LocalDate.of(2032, 5, 13)
                : LocalDate.of(2032, 6, 10);
        BookingFixture fixture = createFixture(date);
        saveUnavailability(fixture.providerId(), date, status);

        ProviderUnavailableException exception = assertThrows(
                ProviderUnavailableException.class,
                () -> directBookingService.bookAppointment(
                        fixture.firstPatientId(),
                        fixture.providerId(),
                        fixture.appointmentTypeId(),
                        atClinicTime(date, 12, 0),
                        null));

        assertInstanceOf(ProviderUnavailableException.class, exception);
        assertEquals(0, appointmentCountForProvider(fixture.providerId()));
    }

    private Object attemptBookingAfterSignal(
            Long patientId,
            BookingFixture fixture,
            Instant startAt,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            return new AssertionError("Concurrent booking start signal timed out");
        }
        try {
            return directBookingService.bookAppointment(
                    patientId,
                    fixture.providerId(),
                    fixture.appointmentTypeId(),
                    startAt,
                    null);
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private BookingFixture createFixture(LocalDate scheduleDate) {
        Specialty specialty = new Specialty();
        specialty.setName(uniqueValue("Specialty"));
        specialty.setDescription("Direct booking test specialty");
        Specialty savedSpecialty = specialtyRepository.saveAndFlush(specialty);

        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName(uniqueValue("Appointment Type"));
        appointmentType.setDurationMinutes(60);
        appointmentType.setSpecialtyId(savedSpecialty.getId());
        appointmentType.setDescription("Direct booking test appointment type");
        appointmentType.setActive(true);
        AppointmentType savedAppointmentType = appointmentTypeRepository.saveAndFlush(appointmentType);

        Provider provider = saveProviderWithSchedule(savedSpecialty.getId(), scheduleDate);
        User firstPatient = saveUser(UserRole.PATIENT, "Patient One");
        User secondPatient = saveUser(UserRole.PATIENT, "Patient Two");

        return new BookingFixture(
                firstPatient.getId(),
                secondPatient.getId(),
                provider.getId(),
                savedSpecialty.getId(),
                savedAppointmentType.getId());
    }

    private Provider saveProviderWithSchedule(Long specialtyId, LocalDate scheduleDate) {
        User providerUser = saveUser(UserRole.PROVIDER, "Test Provider");
        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(specialtyId);
        provider.setLicenseNumber(uniqueValue("LICENSE"));
        provider.setQualifications("Board certified");
        Provider savedProvider = providerRepository.saveAndFlush(provider);

        ProviderSchedule schedule = new ProviderSchedule();
        schedule.setProviderId(savedProvider.getId());
        schedule.setDayOfWeek(scheduleDate.getDayOfWeek());
        schedule.setStartTime(LocalTime.of(9, 0));
        schedule.setEndTime(LocalTime.of(17, 0));
        schedule.setActive(true);
        providerScheduleRepository.saveAndFlush(schedule);
        return savedProvider;
    }

    private User saveUser(UserRole role, String displayName) {
        User user = new User();
        user.setEmail(uniqueValue(role.name().toLowerCase()) + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName(displayName);
        return userRepository.saveAndFlush(user);
    }

    private void saveUnavailability(
            Long providerId, LocalDate date, ProviderUnavailabilityStatus status) {
        ProviderUnavailability unavailability = new ProviderUnavailability();
        unavailability.setProviderId(providerId);
        unavailability.setStartAt(atClinicTime(date, 12, 30));
        unavailability.setEndAt(atClinicTime(date, 13, 30));
        unavailability.setStatus(status);
        unavailability.setReason("Test block");
        providerUnavailabilityRepository.saveAndFlush(unavailability);
    }

    private long appointmentCountForProvider(Long providerId) {
        return appointmentRepository.findAll().stream()
                .filter(appointment -> providerId.equals(appointment.getProviderId()))
                .count();
    }

    private static Instant atClinicTime(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(CLINIC_TIME_ZONE).toInstant();
    }

    private static String uniqueValue(String prefix) {
        return prefix + "-" + UNIQUE_SEQUENCE.incrementAndGet();
    }

    private record BookingFixture(
            Long firstPatientId,
            Long secondPatientId,
            Long providerId,
            Long specialtyId,
            Long appointmentTypeId) {
    }
}

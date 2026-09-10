package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.Specialty;
import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.CancellationReason;
import com.recoverysystem.domain.enums.TimeOfDayPreference;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.exception.AppointmentNotFoundException;
import com.recoverysystem.exception.InvalidWaitlistDateRangeException;
import com.recoverysystem.exception.PreferredProviderSpecialtyMismatchException;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.exception.WaitlistAnchorNotScheduledException;
import com.recoverysystem.exception.WaitlistAnchorOwnershipException;
import com.recoverysystem.exception.WaitlistAppointmentTypeMismatchException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import com.recoverysystem.service.WaitlistEntryCreationService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
class WaitlistEntryCreationServiceTest {

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
    private WaitlistEntryCreationService waitlistEntryCreationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SpecialtyRepository specialtyRepository;

    @Autowired
    private AppointmentTypeRepository appointmentTypeRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private WaitlistEntryRepository waitlistEntryRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void validWaitlistEntryCreatesActiveEntryAndAuditLog() {
        LocalDate appointmentDate = LocalDate.of(2033, 1, 12);
        WaitlistFixture fixture = createFixture(appointmentDate, AppointmentStatus.SCHEDULED);

        WaitlistEntry entry = createEntry(
                fixture,
                appointmentDate,
                appointmentDate.plusDays(14),
                fixture.providerId(),
                TimeOfDayPreference.MORNING);

        assertNotNull(entry.getId());
        assertEquals(WaitlistEntryStatus.ACTIVE, entry.getStatus());
        assertEquals(fixture.patientId(), entry.getPatientId());
        assertEquals(fixture.appointmentId(), entry.getCurrentAppointmentId());
        assertEquals(fixture.appointmentTypeId(), entry.getAppointmentTypeId());
        assertEquals(fixture.providerId(), entry.getPreferredProviderId());
        assertEquals(TimeOfDayPreference.MORNING, entry.getPreferredTimeOfDay());

        List<AuditLog> matchingAuditLogs = auditLogRepository.findAll().stream()
                .filter(log -> "WaitlistEntry".equals(log.getEntityType()))
                .filter(log -> entry.getId().equals(log.getEntityId()))
                .filter(log -> "CREATE".equals(log.getAction()))
                .toList();
        assertEquals(1, matchingAuditLogs.size());
        AuditLog auditLog = matchingAuditLogs.getFirst();
        assertEquals(ActorType.USER, auditLog.getActorType());
        assertEquals(fixture.patientId(), auditLog.getActorUserId());
    }

    @Test
    void nonexistentCurrentAppointmentIsRejected() {
        LocalDate appointmentDate = LocalDate.of(2033, 2, 9);
        WaitlistFixture fixture = createFixture(appointmentDate, AppointmentStatus.SCHEDULED);

        assertThrows(
                AppointmentNotFoundException.class,
                () -> waitlistEntryCreationService.createWaitlistEntry(
                        fixture.patientId(),
                        Long.MAX_VALUE,
                        fixture.appointmentTypeId(),
                        appointmentDate,
                        appointmentDate.plusDays(14),
                        null,
                        TimeOfDayPreference.ANY));

        assertEquals(0, waitlistEntryCountForAppointment(Long.MAX_VALUE));
    }

    @Test
    void anchorAppointmentThatIsNotScheduledIsRejected() {
        LocalDate appointmentDate = LocalDate.of(2033, 3, 9);
        WaitlistFixture fixture = createFixture(appointmentDate, AppointmentStatus.CANCELLED);

        assertThrows(
                WaitlistAnchorNotScheduledException.class,
                () -> createEntry(
                        fixture,
                        appointmentDate,
                        appointmentDate.plusDays(14),
                        null,
                        TimeOfDayPreference.ANY));

        assertEquals(0, waitlistEntryCountForAppointment(fixture.appointmentId()));
    }

    @Test
    void anchorAppointmentOwnedByAnotherPatientIsRejected() {
        LocalDate appointmentDate = LocalDate.of(2033, 4, 13);
        WaitlistFixture fixture = createFixture(appointmentDate, AppointmentStatus.SCHEDULED);

        assertThrows(
                WaitlistAnchorOwnershipException.class,
                () -> waitlistEntryCreationService.createWaitlistEntry(
                        fixture.otherPatientId(),
                        fixture.appointmentId(),
                        fixture.appointmentTypeId(),
                        appointmentDate,
                        appointmentDate.plusDays(14),
                        null,
                        TimeOfDayPreference.ANY));

        assertEquals(0, waitlistEntryCountForAppointment(fixture.appointmentId()));
    }

    @Test
    void appointmentTypeDifferentFromAnchorTypeIsRejected() {
        LocalDate appointmentDate = LocalDate.of(2033, 5, 11);
        WaitlistFixture fixture = createFixture(appointmentDate, AppointmentStatus.SCHEDULED);
        AppointmentType otherAppointmentType = saveAppointmentType(fixture.specialtyId());

        assertThrows(
                WaitlistAppointmentTypeMismatchException.class,
                () -> waitlistEntryCreationService.createWaitlistEntry(
                        fixture.patientId(),
                        fixture.appointmentId(),
                        otherAppointmentType.getId(),
                        appointmentDate,
                        appointmentDate.plusDays(14),
                        null,
                        TimeOfDayPreference.ANY));

        assertEquals(0, waitlistEntryCountForAppointment(fixture.appointmentId()));
    }

    @Test
    void preferredProviderWithMismatchedSpecialtyIsRejected() {
        LocalDate appointmentDate = LocalDate.of(2033, 6, 8);
        WaitlistFixture fixture = createFixture(appointmentDate, AppointmentStatus.SCHEDULED);
        Specialty otherSpecialty = saveSpecialty();
        Provider mismatchedProvider = saveProvider(otherSpecialty.getId());

        assertThrows(
                PreferredProviderSpecialtyMismatchException.class,
                () -> createEntry(
                        fixture,
                        appointmentDate,
                        appointmentDate.plusDays(14),
                        mismatchedProvider.getId(),
                        TimeOfDayPreference.ANY));

        assertEquals(0, waitlistEntryCountForAppointment(fixture.appointmentId()));
    }

    @Test
    void preferredProviderWithMatchingSpecialtyIsAccepted() {
        LocalDate appointmentDate = LocalDate.of(2033, 7, 13);
        WaitlistFixture fixture = createFixture(appointmentDate, AppointmentStatus.SCHEDULED);

        WaitlistEntry entry = createEntry(
                fixture,
                appointmentDate,
                appointmentDate.plusDays(14),
                fixture.providerId(),
                TimeOfDayPreference.AFTERNOON);

        assertNotNull(entry.getId());
        assertEquals(fixture.providerId(), entry.getPreferredProviderId());
        assertEquals(WaitlistEntryStatus.ACTIVE, entry.getStatus());
    }

    @Test
    void nullPreferredProviderSkipsSpecialtyCheckAndSucceeds() {
        LocalDate appointmentDate = LocalDate.of(2033, 8, 10);
        WaitlistFixture fixture = createFixture(appointmentDate, AppointmentStatus.SCHEDULED);

        WaitlistEntry entry = createEntry(
                fixture,
                appointmentDate,
                appointmentDate.plusDays(14),
                null,
                TimeOfDayPreference.ANY);

        assertNotNull(entry.getId());
        assertNull(entry.getPreferredProviderId());
        assertEquals(WaitlistEntryStatus.ACTIVE, entry.getStatus());
    }

    @Test
    void nonexistentPreferredProviderIsRejectedWithExistingException() {
        LocalDate appointmentDate = LocalDate.of(2033, 9, 14);
        WaitlistFixture fixture = createFixture(appointmentDate, AppointmentStatus.SCHEDULED);

        assertThrows(
                ProviderNotFoundException.class,
                () -> createEntry(
                        fixture,
                        appointmentDate,
                        appointmentDate.plusDays(14),
                        Long.MAX_VALUE,
                        TimeOfDayPreference.ANY));

        assertEquals(0, waitlistEntryCountForAppointment(fixture.appointmentId()));
    }

    @Test
    void earliestDateAfterLatestDateIsRejected() {
        LocalDate appointmentDate = LocalDate.of(2033, 10, 12);
        WaitlistFixture fixture = createFixture(appointmentDate, AppointmentStatus.SCHEDULED);

        assertThrows(
                InvalidWaitlistDateRangeException.class,
                () -> createEntry(
                        fixture,
                        appointmentDate.plusDays(14),
                        appointmentDate,
                        null,
                        TimeOfDayPreference.ANY));

        assertEquals(0, waitlistEntryCountForAppointment(fixture.appointmentId()));
    }

    @Test
    void nullPreferredTimeOfDayDefaultsToAny() {
        LocalDate appointmentDate = LocalDate.of(2033, 11, 9);
        WaitlistFixture fixture = createFixture(appointmentDate, AppointmentStatus.SCHEDULED);

        WaitlistEntry entry = createEntry(
                fixture,
                appointmentDate,
                appointmentDate.plusDays(14),
                null,
                null);

        assertEquals(TimeOfDayPreference.ANY, entry.getPreferredTimeOfDay());
        WaitlistEntry reloadedEntry = waitlistEntryRepository.findById(entry.getId()).orElseThrow();
        assertEquals(TimeOfDayPreference.ANY, reloadedEntry.getPreferredTimeOfDay());
    }

    private WaitlistEntry createEntry(
            WaitlistFixture fixture,
            LocalDate earliestDate,
            LocalDate latestDate,
            Long preferredProviderId,
            TimeOfDayPreference preferredTimeOfDay) {
        return waitlistEntryCreationService.createWaitlistEntry(
                fixture.patientId(),
                fixture.appointmentId(),
                fixture.appointmentTypeId(),
                earliestDate,
                latestDate,
                preferredProviderId,
                preferredTimeOfDay);
    }

    private WaitlistFixture createFixture(
            LocalDate appointmentDate, AppointmentStatus appointmentStatus) {
        Specialty specialty = saveSpecialty();
        AppointmentType appointmentType = saveAppointmentType(specialty.getId());
        Provider provider = saveProvider(specialty.getId());
        User patient = saveUser(UserRole.PATIENT);
        User otherPatient = saveUser(UserRole.PATIENT);

        Appointment appointment = new Appointment();
        appointment.setPatientId(patient.getId());
        appointment.setProviderId(provider.getId());
        appointment.setAppointmentTypeId(appointmentType.getId());
        appointment.setStartAt(atClinicTime(appointmentDate, 12));
        appointment.setEndAt(atClinicTime(appointmentDate, 13));
        appointment.setStatus(appointmentStatus);
        if (appointmentStatus == AppointmentStatus.CANCELLED) {
            appointment.setCancellationReason(CancellationReason.PATIENT_CANCELLED);
        }
        Appointment savedAppointment = appointmentRepository.saveAndFlush(appointment);

        return new WaitlistFixture(
                patient.getId(),
                otherPatient.getId(),
                savedAppointment.getId(),
                appointmentType.getId(),
                provider.getId(),
                specialty.getId());
    }

    private Specialty saveSpecialty() {
        Specialty specialty = new Specialty();
        specialty.setName(uniqueValue("Waitlist Specialty"));
        specialty.setDescription("Waitlist creation test specialty");
        return specialtyRepository.saveAndFlush(specialty);
    }

    private AppointmentType saveAppointmentType(Long specialtyId) {
        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName(uniqueValue("Waitlist Appointment Type"));
        appointmentType.setDurationMinutes(60);
        appointmentType.setSpecialtyId(specialtyId);
        appointmentType.setDescription("Waitlist creation test appointment type");
        appointmentType.setActive(true);
        return appointmentTypeRepository.saveAndFlush(appointmentType);
    }

    private Provider saveProvider(Long specialtyId) {
        User providerUser = saveUser(UserRole.PROVIDER);
        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(specialtyId);
        provider.setLicenseNumber(uniqueValue("WAITLIST-LICENSE"));
        provider.setQualifications("Waitlist test provider");
        return providerRepository.saveAndFlush(provider);
    }

    private User saveUser(UserRole role) {
        User user = new User();
        user.setEmail(uniqueValue(role.name().toLowerCase()) + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName("Waitlist test " + role.name().toLowerCase());
        return userRepository.saveAndFlush(user);
    }

    private long waitlistEntryCountForAppointment(Long appointmentId) {
        return waitlistEntryRepository.findAll().stream()
                .filter(entry -> appointmentId.equals(entry.getCurrentAppointmentId()))
                .count();
    }

    private static Instant atClinicTime(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(CLINIC_TIME_ZONE).toInstant();
    }

    private static String uniqueValue(String prefix) {
        return prefix + "-" + UNIQUE_SEQUENCE.incrementAndGet();
    }

    private record WaitlistFixture(
            Long patientId,
            Long otherPatientId,
            Long appointmentId,
            Long appointmentTypeId,
            Long providerId,
            Long specialtyId) {
    }
}

package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.recoverysystem.exception.InvalidWaitlistDateRangeException;
import com.recoverysystem.exception.PreferredProviderSpecialtyMismatchException;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.exception.WaitlistAnchorNotScheduledException;
import com.recoverysystem.exception.WaitlistEntryNotActiveException;
import com.recoverysystem.exception.WaitlistEntryNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import com.recoverysystem.service.WaitlistEntryModificationService;
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
class WaitlistEntryModificationServiceTest {

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
    private WaitlistEntryModificationService waitlistEntryModificationService;

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

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void activeEntryUpdatesAllFieldsAndAuditsBeforeAndAfterValues() throws Exception {
        ModificationFixture fixture = createFixture(
                LocalDate.of(2035, 1, 11),
                AppointmentStatus.SCHEDULED,
                WaitlistEntryStatus.ACTIVE,
                true);
        Provider newProvider = saveProvider(fixture.specialtyId());
        LocalDate newEarliestDate = fixture.initialEarliestDate().plusDays(2);
        LocalDate newLatestDate = fixture.initialLatestDate().plusDays(7);

        WaitlistEntry result = waitlistEntryModificationService.modifyWaitlistEntry(
                fixture.waitlistEntryId(),
                newEarliestDate,
                newLatestDate,
                newProvider.getId(),
                TimeOfDayPreference.AFTERNOON,
                fixture.patientId());

        assertEquals(newEarliestDate, result.getEarliestAppointmentDate());
        assertEquals(newLatestDate, result.getLatestAppointmentDate());
        assertEquals(newProvider.getId(), result.getPreferredProviderId());
        assertEquals(TimeOfDayPreference.AFTERNOON, result.getPreferredTimeOfDay());

        WaitlistEntry reloaded = waitlistEntryRepository.findById(fixture.waitlistEntryId())
                .orElseThrow();
        assertEquals(newEarliestDate, reloaded.getEarliestAppointmentDate());
        assertEquals(newLatestDate, reloaded.getLatestAppointmentDate());
        assertEquals(newProvider.getId(), reloaded.getPreferredProviderId());
        assertEquals(TimeOfDayPreference.AFTERNOON, reloaded.getPreferredTimeOfDay());

        AuditLog auditLog = singleUpdateAudit(fixture.waitlistEntryId());
        assertEquals("WaitlistEntry", auditLog.getEntityType());
        assertEquals("UPDATE", auditLog.getAction());
        assertEquals(ActorType.USER, auditLog.getActorType());
        assertEquals(fixture.patientId(), auditLog.getActorUserId());
        assertNull(auditLog.getReason());

        JsonNode oldValues = objectMapper.readTree(auditLog.getOldValues());
        assertEquals(4, oldValues.size());
        assertEquals(
                fixture.initialEarliestDate().toString(),
                oldValues.get("earliestAppointmentDate").asText());
        assertEquals(
                fixture.initialLatestDate().toString(),
                oldValues.get("latestAppointmentDate").asText());
        assertEquals(
                fixture.initialPreferredProviderId().longValue(),
                oldValues.get("preferredProviderId").asLong());
        assertEquals("MORNING", oldValues.get("preferredTimeOfDay").asText());

        JsonNode newValues = objectMapper.readTree(auditLog.getNewValues());
        assertEquals(4, newValues.size());
        assertEquals(
                newEarliestDate.toString(),
                newValues.get("earliestAppointmentDate").asText());
        assertEquals(
                newLatestDate.toString(),
                newValues.get("latestAppointmentDate").asText());
        assertEquals(newProvider.getId().longValue(), newValues.get("preferredProviderId").asLong());
        assertEquals("AFTERNOON", newValues.get("preferredTimeOfDay").asText());
    }

    @Test
    void anchorAppointmentThatIsNotScheduledIsRejectedWithoutChanges() {
        ModificationFixture fixture = createFixture(
                LocalDate.of(2035, 2, 8),
                AppointmentStatus.CANCELLED,
                WaitlistEntryStatus.ACTIVE,
                true);
        EntrySnapshot before = snapshot(fixture.waitlistEntryId());

        assertThrows(
                WaitlistAnchorNotScheduledException.class,
                () -> modifyWithDefaults(fixture, fixture.providerId(), TimeOfDayPreference.ANY));

        assertEntryUnchanged(fixture.waitlistEntryId(), before);
        assertEquals(0, updateAudits(fixture.waitlistEntryId()).size());
    }

    @Test
    void inactiveEntryIsRejectedWithoutChanges() {
        ModificationFixture fixture = createFixture(
                LocalDate.of(2035, 3, 8),
                AppointmentStatus.SCHEDULED,
                WaitlistEntryStatus.REMOVED,
                true);
        EntrySnapshot before = snapshot(fixture.waitlistEntryId());

        WaitlistEntryNotActiveException exception = assertThrows(
                WaitlistEntryNotActiveException.class,
                () -> modifyWithDefaults(fixture, fixture.providerId(), TimeOfDayPreference.ANY));

        assertTrue(exception.getMessage().contains("REMOVED"));
        assertEntryUnchanged(fixture.waitlistEntryId(), before);
        assertEquals(0, updateAudits(fixture.waitlistEntryId()).size());
    }

    @Test
    void nonexistentWaitlistEntryIsRejected() {
        assertThrows(
                WaitlistEntryNotFoundException.class,
                () -> waitlistEntryModificationService.modifyWaitlistEntry(
                        Long.MAX_VALUE,
                        LocalDate.of(2035, 4, 1),
                        LocalDate.of(2035, 4, 30),
                        null,
                        TimeOfDayPreference.ANY,
                        null));
        assertEquals(0, updateAudits(Long.MAX_VALUE).size());
    }

    @Test
    void preferredProviderWithMismatchedSpecialtyIsRejectedWithoutChanges() {
        ModificationFixture fixture = createFixture(
                LocalDate.of(2035, 5, 10),
                AppointmentStatus.SCHEDULED,
                WaitlistEntryStatus.ACTIVE,
                true);
        Specialty otherSpecialty = saveSpecialty();
        Provider mismatchedProvider = saveProvider(otherSpecialty.getId());
        EntrySnapshot before = snapshot(fixture.waitlistEntryId());

        assertThrows(
                PreferredProviderSpecialtyMismatchException.class,
                () -> modifyWithDefaults(
                        fixture, mismatchedProvider.getId(), TimeOfDayPreference.AFTERNOON));

        assertEntryUnchanged(fixture.waitlistEntryId(), before);
        assertEquals(0, updateAudits(fixture.waitlistEntryId()).size());
    }

    @Test
    void preferredProviderWithMatchingSpecialtyIsAccepted() {
        ModificationFixture fixture = createFixture(
                LocalDate.of(2035, 6, 14),
                AppointmentStatus.SCHEDULED,
                WaitlistEntryStatus.ACTIVE,
                true);
        Provider matchingProvider = saveProvider(fixture.specialtyId());

        modifyWithDefaults(fixture, matchingProvider.getId(), TimeOfDayPreference.AFTERNOON);

        WaitlistEntry reloaded = waitlistEntryRepository.findById(fixture.waitlistEntryId())
                .orElseThrow();
        assertEquals(matchingProvider.getId(), reloaded.getPreferredProviderId());
        assertEquals(1, updateAudits(fixture.waitlistEntryId()).size());
    }

    @Test
    void nullPreferredProviderClearsExistingPreference() {
        ModificationFixture fixture = createFixture(
                LocalDate.of(2035, 7, 12),
                AppointmentStatus.SCHEDULED,
                WaitlistEntryStatus.ACTIVE,
                true);

        modifyWithDefaults(fixture, null, TimeOfDayPreference.ANY);

        WaitlistEntry reloaded = waitlistEntryRepository.findById(fixture.waitlistEntryId())
                .orElseThrow();
        assertNull(reloaded.getPreferredProviderId());
        assertEquals(1, updateAudits(fixture.waitlistEntryId()).size());
    }

    @Test
    void nonexistentPreferredProviderIsRejectedWithoutChanges() {
        ModificationFixture fixture = createFixture(
                LocalDate.of(2035, 8, 9),
                AppointmentStatus.SCHEDULED,
                WaitlistEntryStatus.ACTIVE,
                true);
        EntrySnapshot before = snapshot(fixture.waitlistEntryId());

        assertThrows(
                ProviderNotFoundException.class,
                () -> modifyWithDefaults(fixture, Long.MAX_VALUE, TimeOfDayPreference.ANY));

        assertEntryUnchanged(fixture.waitlistEntryId(), before);
        assertEquals(0, updateAudits(fixture.waitlistEntryId()).size());
    }

    @Test
    void earliestDateAfterLatestDateIsRejectedWithoutChanges() {
        ModificationFixture fixture = createFixture(
                LocalDate.of(2035, 9, 13),
                AppointmentStatus.SCHEDULED,
                WaitlistEntryStatus.ACTIVE,
                true);
        EntrySnapshot before = snapshot(fixture.waitlistEntryId());

        assertThrows(
                InvalidWaitlistDateRangeException.class,
                () -> waitlistEntryModificationService.modifyWaitlistEntry(
                        fixture.waitlistEntryId(),
                        fixture.initialLatestDate().plusDays(1),
                        fixture.initialEarliestDate(),
                        null,
                        TimeOfDayPreference.ANY,
                        fixture.patientId()));

        assertEntryUnchanged(fixture.waitlistEntryId(), before);
        assertEquals(0, updateAudits(fixture.waitlistEntryId()).size());
    }

    @Test
    void nullPreferredTimeOfDayPersistsAny() {
        ModificationFixture fixture = createFixture(
                LocalDate.of(2035, 10, 11),
                AppointmentStatus.SCHEDULED,
                WaitlistEntryStatus.ACTIVE,
                false);

        modifyWithDefaults(fixture, null, null);

        WaitlistEntry reloaded = waitlistEntryRepository.findById(fixture.waitlistEntryId())
                .orElseThrow();
        assertEquals(TimeOfDayPreference.ANY, reloaded.getPreferredTimeOfDay());
    }

    @Test
    void auditActorUsesSystemForNullAndUserForNonNullActor() {
        ModificationFixture systemFixture = createFixture(
                LocalDate.of(2035, 11, 8),
                AppointmentStatus.SCHEDULED,
                WaitlistEntryStatus.ACTIVE,
                false);
        ModificationFixture userFixture = createFixture(
                LocalDate.of(2035, 12, 13),
                AppointmentStatus.SCHEDULED,
                WaitlistEntryStatus.ACTIVE,
                false);
        User actor = saveUser(UserRole.RECEPTIONIST);

        waitlistEntryModificationService.modifyWaitlistEntry(
                systemFixture.waitlistEntryId(),
                systemFixture.initialEarliestDate(),
                systemFixture.initialLatestDate(),
                null,
                TimeOfDayPreference.ANY,
                null);
        waitlistEntryModificationService.modifyWaitlistEntry(
                userFixture.waitlistEntryId(),
                userFixture.initialEarliestDate(),
                userFixture.initialLatestDate(),
                null,
                TimeOfDayPreference.ANY,
                actor.getId());

        AuditLog systemAudit = singleUpdateAudit(systemFixture.waitlistEntryId());
        assertEquals(ActorType.SYSTEM, systemAudit.getActorType());
        assertNull(systemAudit.getActorUserId());
        AuditLog userAudit = singleUpdateAudit(userFixture.waitlistEntryId());
        assertEquals(ActorType.USER, userAudit.getActorType());
        assertEquals(actor.getId(), userAudit.getActorUserId());
    }

    private WaitlistEntry modifyWithDefaults(
            ModificationFixture fixture,
            Long preferredProviderId,
            TimeOfDayPreference preferredTimeOfDay) {
        return waitlistEntryModificationService.modifyWaitlistEntry(
                fixture.waitlistEntryId(),
                fixture.initialEarliestDate().plusDays(1),
                fixture.initialLatestDate().plusDays(1),
                preferredProviderId,
                preferredTimeOfDay,
                fixture.patientId());
    }

    private ModificationFixture createFixture(
            LocalDate appointmentDate,
            AppointmentStatus appointmentStatus,
            WaitlistEntryStatus waitlistEntryStatus,
            boolean withPreferredProvider) {
        Specialty specialty = saveSpecialty();
        AppointmentType appointmentType = saveAppointmentType(specialty.getId());
        Provider provider = saveProvider(specialty.getId());
        User patient = saveUser(UserRole.PATIENT);

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

        LocalDate earliestDate = appointmentDate;
        LocalDate latestDate = appointmentDate.plusDays(14);
        WaitlistEntry waitlistEntry = new WaitlistEntry();
        waitlistEntry.setPatientId(patient.getId());
        waitlistEntry.setCurrentAppointmentId(savedAppointment.getId());
        waitlistEntry.setAppointmentTypeId(appointmentType.getId());
        waitlistEntry.setPreferredProviderId(withPreferredProvider ? provider.getId() : null);
        waitlistEntry.setEarliestAppointmentDate(earliestDate);
        waitlistEntry.setLatestAppointmentDate(latestDate);
        waitlistEntry.setPreferredTimeOfDay(TimeOfDayPreference.MORNING);
        waitlistEntry.setStatus(waitlistEntryStatus);
        WaitlistEntry savedWaitlistEntry = waitlistEntryRepository.saveAndFlush(waitlistEntry);

        return new ModificationFixture(
                patient.getId(),
                provider.getId(),
                specialty.getId(),
                savedWaitlistEntry.getId(),
                earliestDate,
                latestDate,
                savedWaitlistEntry.getPreferredProviderId());
    }

    private Specialty saveSpecialty() {
        Specialty specialty = new Specialty();
        specialty.setName(uniqueValue("Modification Specialty"));
        specialty.setDescription("Waitlist modification test specialty");
        return specialtyRepository.saveAndFlush(specialty);
    }

    private AppointmentType saveAppointmentType(Long specialtyId) {
        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName(uniqueValue("Modification Appointment Type"));
        appointmentType.setDurationMinutes(60);
        appointmentType.setSpecialtyId(specialtyId);
        appointmentType.setDescription("Waitlist modification test appointment type");
        appointmentType.setActive(true);
        return appointmentTypeRepository.saveAndFlush(appointmentType);
    }

    private Provider saveProvider(Long specialtyId) {
        User providerUser = saveUser(UserRole.PROVIDER);
        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(specialtyId);
        provider.setLicenseNumber(uniqueValue("MODIFICATION-LICENSE"));
        provider.setQualifications("Waitlist modification test provider");
        return providerRepository.saveAndFlush(provider);
    }

    private User saveUser(UserRole role) {
        User user = new User();
        user.setEmail(uniqueValue(role.name().toLowerCase()) + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName("Waitlist modification " + role.name().toLowerCase());
        return userRepository.saveAndFlush(user);
    }

    private EntrySnapshot snapshot(Long waitlistEntryId) {
        WaitlistEntry waitlistEntry = waitlistEntryRepository.findById(waitlistEntryId)
                .orElseThrow();
        return new EntrySnapshot(
                waitlistEntry.getEarliestAppointmentDate(),
                waitlistEntry.getLatestAppointmentDate(),
                waitlistEntry.getPreferredProviderId(),
                waitlistEntry.getPreferredTimeOfDay(),
                waitlistEntry.getUpdatedAt());
    }

    private void assertEntryUnchanged(Long waitlistEntryId, EntrySnapshot before) {
        assertEquals(before, snapshot(waitlistEntryId));
    }

    private AuditLog singleUpdateAudit(Long waitlistEntryId) {
        List<AuditLog> auditLogs = updateAudits(waitlistEntryId);
        assertEquals(1, auditLogs.size());
        return auditLogs.getFirst();
    }

    private List<AuditLog> updateAudits(Long waitlistEntryId) {
        return auditLogRepository.findAll().stream()
                .filter(log -> "WaitlistEntry".equals(log.getEntityType()))
                .filter(log -> waitlistEntryId.equals(log.getEntityId()))
                .filter(log -> "UPDATE".equals(log.getAction()))
                .toList();
    }

    private static Instant atClinicTime(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(CLINIC_TIME_ZONE).toInstant();
    }

    private static String uniqueValue(String prefix) {
        return prefix + "-" + UNIQUE_SEQUENCE.incrementAndGet();
    }

    private record ModificationFixture(
            Long patientId,
            Long providerId,
            Long specialtyId,
            Long waitlistEntryId,
            LocalDate initialEarliestDate,
            LocalDate initialLatestDate,
            Long initialPreferredProviderId) {
    }

    private record EntrySnapshot(
            LocalDate earliestAppointmentDate,
            LocalDate latestAppointmentDate,
            Long preferredProviderId,
            TimeOfDayPreference preferredTimeOfDay,
            Instant updatedAt) {
    }
}

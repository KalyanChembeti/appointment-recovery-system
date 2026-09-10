package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.entity.Specialty;
import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.CancellationReason;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.domain.enums.TimeOfDayPreference;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.exception.WaitlistEntryNotActiveException;
import com.recoverysystem.exception.WaitlistEntryNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import com.recoverysystem.service.WaitlistEntryRemovalService;
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
class WaitlistEntryRemovalServiceTest {

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
    private WaitlistEntryRemovalService waitlistEntryRemovalService;

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
    private RecoveryJobRepository recoveryJobRepository;

    @Autowired
    private SlotOfferRepository slotOfferRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void activeEntryWithTwoOfferedSlotsIsRemovedAndFullyAudited() {
        RemovalFixture fixture = createFixture(
                LocalDate.of(2034, 1, 11), WaitlistEntryStatus.ACTIVE);
        SlotOffer firstOffer = saveSlotOffer(fixture, SlotOfferStatus.OFFERED);
        SlotOffer secondOffer = saveSlotOffer(fixture, SlotOfferStatus.OFFERED);

        WaitlistEntry removedEntry = waitlistEntryRemovalService.removeWaitlistEntry(
                fixture.waitlistEntryId(), fixture.patientId());

        assertEquals(WaitlistEntryStatus.REMOVED, removedEntry.getStatus());
        assertEquals(
                WaitlistEntryStatus.REMOVED,
                waitlistEntryRepository.findById(fixture.waitlistEntryId())
                        .orElseThrow()
                        .getStatus());
        assertEquals(
                SlotOfferStatus.CANCELLED,
                slotOfferRepository.findById(firstOffer.getId()).orElseThrow().getStatus());
        assertEquals(
                SlotOfferStatus.CANCELLED,
                slotOfferRepository.findById(secondOffer.getId()).orElseThrow().getStatus());

        List<AuditLog> auditLogs = operationAuditLogs(
                fixture.waitlistEntryId(), List.of(firstOffer.getId(), secondOffer.getId()));
        assertEquals(3, auditLogs.size());
        List<AuditLog> removeLogs = auditLogs.stream()
                .filter(log -> "WaitlistEntry".equals(log.getEntityType()))
                .filter(log -> "REMOVE".equals(log.getAction()))
                .toList();
        assertEquals(1, removeLogs.size());
        assertNull(removeLogs.getFirst().getReason());

        List<AuditLog> cancelLogs = auditLogs.stream()
                .filter(log -> "SlotOffer".equals(log.getEntityType()))
                .filter(log -> "CANCEL".equals(log.getAction()))
                .toList();
        assertEquals(2, cancelLogs.size());
        assertTrue(cancelLogs.stream().allMatch(log -> "ENTRY_REMOVED".equals(log.getReason())));
    }

    @Test
    void activeEntryWithNoSlotOffersIsRemovedWithOnlyRemoveAudit() {
        RemovalFixture fixture = createFixture(
                LocalDate.of(2034, 2, 8), WaitlistEntryStatus.ACTIVE);

        WaitlistEntry removedEntry = waitlistEntryRemovalService.removeWaitlistEntry(
                fixture.waitlistEntryId(), fixture.patientId());

        assertEquals(WaitlistEntryStatus.REMOVED, removedEntry.getStatus());
        List<AuditLog> auditLogs = operationAuditLogs(fixture.waitlistEntryId(), List.of());
        assertEquals(1, auditLogs.size());
        assertEquals("WaitlistEntry", auditLogs.getFirst().getEntityType());
        assertEquals("REMOVE", auditLogs.getFirst().getAction());
    }

    @Test
    void mixedSiblingStatusesCancelAndAuditOnlyOfferedSlot() {
        RemovalFixture fixture = createFixture(
                LocalDate.of(2034, 3, 8), WaitlistEntryStatus.ACTIVE);
        SlotOffer offeredSlot = saveSlotOffer(fixture, SlotOfferStatus.OFFERED);
        SlotOffer declinedSlot = saveSlotOffer(fixture, SlotOfferStatus.DECLINED);
        Instant declinedUpdatedAtBeforeRemoval = declinedSlot.getUpdatedAt();

        waitlistEntryRemovalService.removeWaitlistEntry(
                fixture.waitlistEntryId(), fixture.patientId());

        SlotOffer reloadedOfferedSlot = slotOfferRepository.findById(offeredSlot.getId()).orElseThrow();
        SlotOffer reloadedDeclinedSlot = slotOfferRepository.findById(declinedSlot.getId()).orElseThrow();
        assertEquals(SlotOfferStatus.CANCELLED, reloadedOfferedSlot.getStatus());
        assertEquals(SlotOfferStatus.DECLINED, reloadedDeclinedSlot.getStatus());
        assertEquals(declinedUpdatedAtBeforeRemoval, reloadedDeclinedSlot.getUpdatedAt());

        List<AuditLog> auditLogs = operationAuditLogs(
                fixture.waitlistEntryId(), List.of(offeredSlot.getId(), declinedSlot.getId()));
        assertEquals(2, auditLogs.size());
        assertEquals(
                1,
                auditLogs.stream()
                        .filter(log -> "SlotOffer".equals(log.getEntityType()))
                        .filter(log -> offeredSlot.getId().equals(log.getEntityId()))
                        .filter(log -> "CANCEL".equals(log.getAction()))
                        .count());
        assertEquals(
                0,
                auditLogs.stream()
                        .filter(log -> "SlotOffer".equals(log.getEntityType()))
                        .filter(log -> declinedSlot.getId().equals(log.getEntityId()))
                        .count());
    }

    @Test
    void alreadyRemovedEntryIsRejectedWithoutChangingEntryOfferOrAudit() {
        RemovalFixture fixture = createFixture(
                LocalDate.of(2034, 4, 12), WaitlistEntryStatus.REMOVED);
        SlotOffer offeredSlot = saveSlotOffer(fixture, SlotOfferStatus.OFFERED);
        WaitlistEntry entryBeforeRemoval = waitlistEntryRepository.findById(fixture.waitlistEntryId())
                .orElseThrow();
        Instant entryUpdatedAtBeforeRemoval = entryBeforeRemoval.getUpdatedAt();
        Instant offerUpdatedAtBeforeRemoval = offeredSlot.getUpdatedAt();

        WaitlistEntryNotActiveException exception = assertThrows(
                WaitlistEntryNotActiveException.class,
                () -> waitlistEntryRemovalService.removeWaitlistEntry(
                        fixture.waitlistEntryId(), fixture.patientId()));

        assertTrue(exception.getMessage().contains("REMOVED"));
        WaitlistEntry reloadedEntry = waitlistEntryRepository.findById(fixture.waitlistEntryId())
                .orElseThrow();
        SlotOffer reloadedOffer = slotOfferRepository.findById(offeredSlot.getId()).orElseThrow();
        assertEquals(WaitlistEntryStatus.REMOVED, reloadedEntry.getStatus());
        assertEquals(entryUpdatedAtBeforeRemoval, reloadedEntry.getUpdatedAt());
        assertEquals(SlotOfferStatus.OFFERED, reloadedOffer.getStatus());
        assertEquals(offerUpdatedAtBeforeRemoval, reloadedOffer.getUpdatedAt());
        assertEquals(
                0,
                operationAuditLogs(fixture.waitlistEntryId(), List.of(offeredSlot.getId())).size());
    }

    @Test
    void fulfilledEntryIsRejectedAsNotActive() {
        RemovalFixture fixture = createFixture(
                LocalDate.of(2034, 5, 10), WaitlistEntryStatus.FULFILLED);

        WaitlistEntryNotActiveException exception = assertThrows(
                WaitlistEntryNotActiveException.class,
                () -> waitlistEntryRemovalService.removeWaitlistEntry(
                        fixture.waitlistEntryId(), fixture.patientId()));

        assertTrue(exception.getMessage().contains("FULFILLED"));
        assertEquals(
                WaitlistEntryStatus.FULFILLED,
                waitlistEntryRepository.findById(fixture.waitlistEntryId())
                        .orElseThrow()
                        .getStatus());
        assertEquals(0, operationAuditLogs(fixture.waitlistEntryId(), List.of()).size());
    }

    @Test
    void nonexistentWaitlistEntryIsRejected() {
        assertThrows(
                WaitlistEntryNotFoundException.class,
                () -> waitlistEntryRemovalService.removeWaitlistEntry(Long.MAX_VALUE, null));
        assertEquals(0, operationAuditLogs(Long.MAX_VALUE, List.of()).size());
    }

    @Test
    void nullActorProducesSystemAuditRows() {
        RemovalFixture fixture = createFixture(
                LocalDate.of(2034, 6, 14), WaitlistEntryStatus.ACTIVE);
        SlotOffer offeredSlot = saveSlotOffer(fixture, SlotOfferStatus.OFFERED);

        waitlistEntryRemovalService.removeWaitlistEntry(fixture.waitlistEntryId(), null);

        List<AuditLog> auditLogs = operationAuditLogs(
                fixture.waitlistEntryId(), List.of(offeredSlot.getId()));
        assertEquals(2, auditLogs.size());
        assertTrue(auditLogs.stream().allMatch(log -> log.getActorType() == ActorType.SYSTEM));
        assertTrue(auditLogs.stream().allMatch(log -> log.getActorUserId() == null));
    }

    @Test
    void nonNullActorProducesUserAuditRows() {
        RemovalFixture fixture = createFixture(
                LocalDate.of(2034, 7, 12), WaitlistEntryStatus.ACTIVE);
        SlotOffer offeredSlot = saveSlotOffer(fixture, SlotOfferStatus.OFFERED);
        User actor = saveUser(UserRole.RECEPTIONIST);

        waitlistEntryRemovalService.removeWaitlistEntry(
                fixture.waitlistEntryId(), actor.getId());

        List<AuditLog> auditLogs = operationAuditLogs(
                fixture.waitlistEntryId(), List.of(offeredSlot.getId()));
        assertEquals(2, auditLogs.size());
        assertTrue(auditLogs.stream().allMatch(log -> log.getActorType() == ActorType.USER));
        assertTrue(auditLogs.stream().allMatch(log -> actor.getId().equals(log.getActorUserId())));
    }

    private RemovalFixture createFixture(
            LocalDate appointmentDate, WaitlistEntryStatus waitlistStatus) {
        Specialty specialty = saveSpecialty();
        AppointmentType appointmentType = saveAppointmentType(specialty.getId());
        Provider provider = saveProvider(specialty.getId());
        User patient = saveUser(UserRole.PATIENT);
        Appointment anchorAppointment = saveAppointment(
                patient.getId(),
                provider.getId(),
                appointmentType.getId(),
                atClinicTime(appointmentDate, 12),
                atClinicTime(appointmentDate, 13),
                AppointmentStatus.SCHEDULED);

        WaitlistEntry waitlistEntry = new WaitlistEntry();
        waitlistEntry.setPatientId(patient.getId());
        waitlistEntry.setCurrentAppointmentId(anchorAppointment.getId());
        waitlistEntry.setAppointmentTypeId(appointmentType.getId());
        waitlistEntry.setEarliestAppointmentDate(appointmentDate);
        waitlistEntry.setLatestAppointmentDate(appointmentDate.plusDays(14));
        waitlistEntry.setPreferredTimeOfDay(TimeOfDayPreference.ANY);
        waitlistEntry.setStatus(waitlistStatus);
        WaitlistEntry savedWaitlistEntry = waitlistEntryRepository.saveAndFlush(waitlistEntry);

        return new RemovalFixture(
                patient.getId(),
                provider.getId(),
                appointmentType.getId(),
                savedWaitlistEntry.getId());
    }

    private SlotOffer saveSlotOffer(RemovalFixture fixture, SlotOfferStatus status) {
        long sequence = UNIQUE_SEQUENCE.incrementAndGet();
        Instant sourceStart = Instant.parse("2040-01-01T00:00:00Z").plusSeconds(sequence * 7200);
        Appointment sourceAppointment = saveAppointment(
                fixture.patientId(),
                fixture.providerId(),
                fixture.appointmentTypeId(),
                sourceStart,
                sourceStart.plusSeconds(3600),
                AppointmentStatus.CANCELLED);

        RecoveryJob recoveryJob = new RecoveryJob();
        recoveryJob.setSourceAppointmentId(sourceAppointment.getId());
        recoveryJob.setStatus(RecoveryJobStatus.OPEN);
        RecoveryJob savedRecoveryJob = recoveryJobRepository.saveAndFlush(recoveryJob);

        SlotOffer slotOffer = new SlotOffer();
        slotOffer.setRecoveryJobId(savedRecoveryJob.getId());
        slotOffer.setWaitlistEntryId(fixture.waitlistEntryId());
        slotOffer.setStatus(status);
        slotOffer.setExpiresAt(Instant.now().plusSeconds(3600));
        return slotOfferRepository.saveAndFlush(slotOffer);
    }

    private Appointment saveAppointment(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Instant startAt,
            Instant endAt,
            AppointmentStatus status) {
        Appointment appointment = new Appointment();
        appointment.setPatientId(patientId);
        appointment.setProviderId(providerId);
        appointment.setAppointmentTypeId(appointmentTypeId);
        appointment.setStartAt(startAt);
        appointment.setEndAt(endAt);
        appointment.setStatus(status);
        if (status == AppointmentStatus.CANCELLED) {
            appointment.setCancellationReason(CancellationReason.PATIENT_CANCELLED);
        }
        return appointmentRepository.saveAndFlush(appointment);
    }

    private Specialty saveSpecialty() {
        Specialty specialty = new Specialty();
        specialty.setName(uniqueValue("Removal Specialty"));
        specialty.setDescription("Waitlist removal test specialty");
        return specialtyRepository.saveAndFlush(specialty);
    }

    private AppointmentType saveAppointmentType(Long specialtyId) {
        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName(uniqueValue("Removal Appointment Type"));
        appointmentType.setDurationMinutes(60);
        appointmentType.setSpecialtyId(specialtyId);
        appointmentType.setDescription("Waitlist removal test appointment type");
        appointmentType.setActive(true);
        return appointmentTypeRepository.saveAndFlush(appointmentType);
    }

    private Provider saveProvider(Long specialtyId) {
        User providerUser = saveUser(UserRole.PROVIDER);
        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(specialtyId);
        provider.setLicenseNumber(uniqueValue("REMOVAL-LICENSE"));
        provider.setQualifications("Waitlist removal test provider");
        return providerRepository.saveAndFlush(provider);
    }

    private User saveUser(UserRole role) {
        User user = new User();
        user.setEmail(uniqueValue(role.name().toLowerCase()) + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName("Waitlist removal " + role.name().toLowerCase());
        return userRepository.saveAndFlush(user);
    }

    private List<AuditLog> operationAuditLogs(Long waitlistEntryId, List<Long> slotOfferIds) {
        return auditLogRepository.findAll().stream()
                .filter(log ->
                        ("WaitlistEntry".equals(log.getEntityType())
                                        && waitlistEntryId.equals(log.getEntityId()))
                                || ("SlotOffer".equals(log.getEntityType())
                                        && slotOfferIds.contains(log.getEntityId())))
                .toList();
    }

    private static Instant atClinicTime(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(CLINIC_TIME_ZONE).toInstant();
    }

    private static String uniqueValue(String prefix) {
        return prefix + "-" + UNIQUE_SEQUENCE.incrementAndGet();
    }

    private record RemovalFixture(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Long waitlistEntryId) {
    }
}

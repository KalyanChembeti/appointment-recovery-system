package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.ProviderUnavailability;
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.entity.Specialty;
import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.CancellationReason;
import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.domain.enums.TimeOfDayPreference;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.exception.AppointmentNotFoundException;
import com.recoverysystem.exception.AppointmentNotScheduledException;
import com.recoverysystem.exception.InvalidCancellationReasonException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import com.recoverysystem.service.AppointmentCancellationService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
class AppointmentCancellationServiceTest {

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
    private AppointmentCancellationService appointmentCancellationService;

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
    private ProviderUnavailabilityRepository providerUnavailabilityRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void simpleCancellationCreatesOpenRecoveryJobAndTwoAuditRows() {
        CancellationFixture fixture = createFixture(AppointmentStatus.SCHEDULED);

        Appointment result = appointmentCancellationService.cancelAppointment(
                fixture.appointmentId(), CancellationReason.PATIENT_CANCELLED, fixture.patientId());

        assertEquals(AppointmentStatus.CANCELLED, result.getStatus());
        assertEquals(CancellationReason.PATIENT_CANCELLED, result.getCancellationReason());
        Appointment reloaded = appointmentRepository.findById(fixture.appointmentId()).orElseThrow();
        assertEquals(AppointmentStatus.CANCELLED, reloaded.getStatus());
        assertEquals(CancellationReason.PATIENT_CANCELLED, reloaded.getCancellationReason());

        RecoveryJob recoveryJob = recoveryJobFor(fixture.appointmentId());
        assertEquals(RecoveryJobStatus.OPEN, recoveryJob.getStatus());
        List<AuditLog> audits = operationAudits(fixture.appointmentId(), List.of(), List.of());
        assertEquals(2, audits.size());
        assertEquals(1, matchingAudits(audits, "Appointment", fixture.appointmentId(), "CANCEL"));
        assertEquals(1, matchingAudits(audits, "RecoveryJob", recoveryJob.getId(), "CREATE"));
    }

    @Test
    void twoActiveEntriesAndOffersAreReconciledAndFullyAudited() {
        CancellationFixture fixture = createFixture(AppointmentStatus.SCHEDULED);
        WaitlistEntry firstEntry = saveWaitlistEntry(fixture, fixture.appointmentId());
        WaitlistEntry secondEntry = saveWaitlistEntry(fixture, fixture.appointmentId());
        SlotOffer firstOffer = saveSlotOffer(fixture, firstEntry.getId(), SlotOfferStatus.OFFERED);
        SlotOffer secondOffer = saveSlotOffer(fixture, secondEntry.getId(), SlotOfferStatus.OFFERED);

        appointmentCancellationService.cancelAppointment(
                fixture.appointmentId(), CancellationReason.STAFF_CANCELLED, fixture.patientId());

        assertEquals(WaitlistEntryStatus.REMOVED, reloadedEntry(firstEntry).getStatus());
        assertEquals(WaitlistEntryStatus.REMOVED, reloadedEntry(secondEntry).getStatus());
        assertEquals(SlotOfferStatus.CANCELLED, reloadedOffer(firstOffer).getStatus());
        assertEquals(SlotOfferStatus.CANCELLED, reloadedOffer(secondOffer).getStatus());
        RecoveryJob recoveryJob = recoveryJobFor(fixture.appointmentId());

        List<AuditLog> audits = operationAudits(
                fixture.appointmentId(),
                List.of(firstEntry.getId(), secondEntry.getId()),
                List.of(firstOffer.getId(), secondOffer.getId()));
        assertEquals(6, audits.size());
        assertEquals(2, audits.stream()
                .filter(log -> "WaitlistEntry".equals(log.getEntityType()))
                .filter(log -> "REMOVE".equals(log.getAction()))
                .count());
        List<AuditLog> offerAudits = audits.stream()
                .filter(log -> "SlotOffer".equals(log.getEntityType()))
                .toList();
        assertEquals(2, offerAudits.size());
        assertTrue(offerAudits.stream()
                .allMatch(log -> "APPOINTMENT_CANCELLED".equals(log.getReason())));
        assertEquals(1, matchingAudits(audits, "RecoveryJob", recoveryJob.getId(), "CREATE"));
    }

    @Test
    void declinedOfferIsUntouchedWhileItsActiveEntryIsRemoved() {
        CancellationFixture fixture = createFixture(AppointmentStatus.SCHEDULED);
        WaitlistEntry offeredEntry = saveWaitlistEntry(fixture, fixture.appointmentId());
        WaitlistEntry declinedEntry = saveWaitlistEntry(fixture, fixture.appointmentId());
        SlotOffer offered = saveSlotOffer(fixture, offeredEntry.getId(), SlotOfferStatus.OFFERED);
        SlotOffer declined = saveSlotOffer(fixture, declinedEntry.getId(), SlotOfferStatus.DECLINED);
        Instant declinedUpdatedAt = declined.getUpdatedAt();

        appointmentCancellationService.cancelAppointment(
                fixture.appointmentId(), CancellationReason.PATIENT_CANCELLED, null);

        assertEquals(WaitlistEntryStatus.REMOVED, reloadedEntry(offeredEntry).getStatus());
        assertEquals(WaitlistEntryStatus.REMOVED, reloadedEntry(declinedEntry).getStatus());
        assertEquals(SlotOfferStatus.CANCELLED, reloadedOffer(offered).getStatus());
        SlotOffer reloadedDeclined = reloadedOffer(declined);
        assertEquals(SlotOfferStatus.DECLINED, reloadedDeclined.getStatus());
        assertEquals(declinedUpdatedAt, reloadedDeclined.getUpdatedAt());

        List<AuditLog> audits = operationAudits(
                fixture.appointmentId(),
                List.of(offeredEntry.getId(), declinedEntry.getId()),
                List.of(offered.getId(), declined.getId()));
        assertEquals(5, audits.size());
        assertEquals(0, audits.stream()
                .filter(log -> "SlotOffer".equals(log.getEntityType()))
                .filter(log -> declined.getId().equals(log.getEntityId()))
                .count());
    }

    @Test
    void entryAnchoredToAnotherAppointmentIsUntouchedAndUnaudited() {
        CancellationFixture cancelledFixture = createFixture(AppointmentStatus.SCHEDULED);
        CancellationFixture otherFixture = createFixture(AppointmentStatus.SCHEDULED);
        WaitlistEntry otherEntry = saveWaitlistEntry(otherFixture, otherFixture.appointmentId());
        Instant otherUpdatedAt = otherEntry.getUpdatedAt();

        appointmentCancellationService.cancelAppointment(
                cancelledFixture.appointmentId(), CancellationReason.STAFF_CANCELLED, null);

        WaitlistEntry reloaded = reloadedEntry(otherEntry);
        assertEquals(WaitlistEntryStatus.ACTIVE, reloaded.getStatus());
        assertEquals(otherUpdatedAt, reloaded.getUpdatedAt());
        assertEquals(0, auditLogRepository.findAll().stream()
                .filter(log -> "WaitlistEntry".equals(log.getEntityType()))
                .filter(log -> otherEntry.getId().equals(log.getEntityId()))
                .count());
    }

    @Test
    void activeOverlappingBlockSuppressesJobCreationButStillReconciles() {
        CancellationFixture fixture = createFixture(AppointmentStatus.SCHEDULED);
        WaitlistEntry entry = saveWaitlistEntry(fixture, fixture.appointmentId());
        SlotOffer offer = saveSlotOffer(fixture, entry.getId(), SlotOfferStatus.OFFERED);
        saveBlock(fixture, ProviderUnavailabilityStatus.ACTIVE);

        appointmentCancellationService.cancelAppointment(
                fixture.appointmentId(), CancellationReason.STAFF_CANCELLED, fixture.patientId());

        assertEquals(AppointmentStatus.CANCELLED,
                appointmentRepository.findById(fixture.appointmentId()).orElseThrow().getStatus());
        assertEquals(WaitlistEntryStatus.REMOVED, reloadedEntry(entry).getStatus());
        assertEquals(SlotOfferStatus.CANCELLED, reloadedOffer(offer).getStatus());
        assertTrue(recoveryJobsFor(fixture.appointmentId()).isEmpty());
        List<AuditLog> audits = operationAudits(
                fixture.appointmentId(), List.of(entry.getId()), List.of(offer.getId()));
        assertEquals(3, audits.size());
        assertEquals(0, audits.stream()
                .filter(log -> "RecoveryJob".equals(log.getEntityType()))
                .filter(log -> "CREATE".equals(log.getAction()))
                .count());
    }

    @Test
    void pendingOverlappingBlockDoesNotSuppressRecoveryJobCreation() {
        CancellationFixture fixture = createFixture(AppointmentStatus.SCHEDULED);
        saveBlock(fixture, ProviderUnavailabilityStatus.PENDING);

        appointmentCancellationService.cancelAppointment(
                fixture.appointmentId(), CancellationReason.PATIENT_CANCELLED, null);

        assertEquals(RecoveryJobStatus.OPEN, recoveryJobFor(fixture.appointmentId()).getStatus());
        assertEquals(1, operationAudits(fixture.appointmentId(), List.of(), List.of()).stream()
                .filter(log -> "RecoveryJob".equals(log.getEntityType()))
                .filter(log -> "CREATE".equals(log.getAction()))
                .count());
    }

    @Test
    void alreadyCancelledAppointmentIsRejectedWithoutChanges() {
        CancellationFixture fixture = createFixture(AppointmentStatus.CANCELLED);
        Appointment before = appointmentRepository.findById(fixture.appointmentId()).orElseThrow();
        Instant updatedAt = before.getUpdatedAt();
        long auditCount = auditLogRepository.count();
        long recoveryCount = recoveryJobRepository.count();

        assertThrows(
                AppointmentNotScheduledException.class,
                () -> appointmentCancellationService.cancelAppointment(
                        fixture.appointmentId(),
                        CancellationReason.STAFF_CANCELLED,
                        fixture.patientId()));

        Appointment reloaded = appointmentRepository.findById(fixture.appointmentId()).orElseThrow();
        assertEquals(AppointmentStatus.CANCELLED, reloaded.getStatus());
        assertEquals(CancellationReason.PATIENT_CANCELLED, reloaded.getCancellationReason());
        assertEquals(updatedAt, reloaded.getUpdatedAt());
        assertEquals(auditCount, auditLogRepository.count());
        assertEquals(recoveryCount, recoveryJobRepository.count());
    }

    @Test
    void rescheduledReasonIsRejectedWithoutChanges() {
        CancellationFixture fixture = createFixture(AppointmentStatus.SCHEDULED);
        WaitlistEntry entry = saveWaitlistEntry(fixture, fixture.appointmentId());
        Appointment before = appointmentRepository.findById(fixture.appointmentId()).orElseThrow();
        Instant updatedAt = before.getUpdatedAt();
        long auditCount = auditLogRepository.count();

        assertThrows(
                InvalidCancellationReasonException.class,
                () -> appointmentCancellationService.cancelAppointment(
                        fixture.appointmentId(), CancellationReason.RESCHEDULED, null));

        Appointment reloaded = appointmentRepository.findById(fixture.appointmentId()).orElseThrow();
        assertEquals(AppointmentStatus.SCHEDULED, reloaded.getStatus());
        assertNull(reloaded.getCancellationReason());
        assertEquals(updatedAt, reloaded.getUpdatedAt());
        assertEquals(WaitlistEntryStatus.ACTIVE, reloadedEntry(entry).getStatus());
        assertTrue(recoveryJobsFor(fixture.appointmentId()).isEmpty());
        assertEquals(auditCount, auditLogRepository.count());
    }

    @Test
    void nonexistentAppointmentIsRejected() {
        long auditCount = auditLogRepository.count();
        long recoveryCount = recoveryJobRepository.count();

        assertThrows(
                AppointmentNotFoundException.class,
                () -> appointmentCancellationService.cancelAppointment(
                        Long.MAX_VALUE, CancellationReason.PATIENT_CANCELLED, null));

        assertEquals(auditCount, auditLogRepository.count());
        assertEquals(recoveryCount, recoveryJobRepository.count());
    }

    @Test
    void actorAttributionIsConsistentAcrossEachCancellationCascade() {
        CancellationFixture systemFixture = createFixture(AppointmentStatus.SCHEDULED);
        WaitlistEntry systemEntry = saveWaitlistEntry(systemFixture, systemFixture.appointmentId());
        SlotOffer systemOffer =
                saveSlotOffer(systemFixture, systemEntry.getId(), SlotOfferStatus.OFFERED);
        appointmentCancellationService.cancelAppointment(
                systemFixture.appointmentId(), CancellationReason.PATIENT_CANCELLED, null);

        User actor = saveUser(UserRole.RECEPTIONIST);
        CancellationFixture userFixture = createFixture(AppointmentStatus.SCHEDULED);
        WaitlistEntry userEntry = saveWaitlistEntry(userFixture, userFixture.appointmentId());
        SlotOffer userOffer = saveSlotOffer(userFixture, userEntry.getId(), SlotOfferStatus.OFFERED);
        appointmentCancellationService.cancelAppointment(
                userFixture.appointmentId(), CancellationReason.STAFF_CANCELLED, actor.getId());

        List<AuditLog> systemAudits = operationAudits(
                systemFixture.appointmentId(),
                List.of(systemEntry.getId()),
                List.of(systemOffer.getId()));
        assertEquals(4, systemAudits.size());
        assertTrue(systemAudits.stream()
                .allMatch(log -> log.getActorType() == ActorType.SYSTEM));
        assertTrue(systemAudits.stream().allMatch(log -> log.getActorUserId() == null));

        List<AuditLog> userAudits = operationAudits(
                userFixture.appointmentId(),
                List.of(userEntry.getId()),
                List.of(userOffer.getId()));
        assertEquals(4, userAudits.size());
        assertTrue(userAudits.stream().allMatch(log -> log.getActorType() == ActorType.USER));
        assertTrue(userAudits.stream()
                .allMatch(log -> actor.getId().equals(log.getActorUserId())));
    }

    @Test
    void patientAndStaffCancellationReasonsBothPersist() {
        CancellationFixture patientFixture = createFixture(AppointmentStatus.SCHEDULED);
        CancellationFixture staffFixture = createFixture(AppointmentStatus.SCHEDULED);

        appointmentCancellationService.cancelAppointment(
                patientFixture.appointmentId(), CancellationReason.PATIENT_CANCELLED, null);
        appointmentCancellationService.cancelAppointment(
                staffFixture.appointmentId(), CancellationReason.STAFF_CANCELLED, null);

        assertEquals(
                CancellationReason.PATIENT_CANCELLED,
                appointmentRepository.findById(patientFixture.appointmentId())
                        .orElseThrow()
                        .getCancellationReason());
        assertEquals(
                CancellationReason.STAFF_CANCELLED,
                appointmentRepository.findById(staffFixture.appointmentId())
                        .orElseThrow()
                        .getCancellationReason());
    }

    private CancellationFixture createFixture(AppointmentStatus appointmentStatus) {
        Specialty specialty = saveSpecialty();
        AppointmentType appointmentType = saveAppointmentType(specialty.getId());
        Provider provider = saveProvider(specialty.getId());
        User patient = saveUser(UserRole.PATIENT);
        long sequence = UNIQUE_SEQUENCE.incrementAndGet();
        Instant startAt = Instant.parse("2044-01-01T14:00:00Z")
                .plusSeconds(sequence * 172800);
        Appointment appointment = saveAppointment(
                patient.getId(),
                provider.getId(),
                appointmentType.getId(),
                startAt,
                startAt.plusSeconds(3600),
                appointmentStatus);
        return new CancellationFixture(
                patient.getId(),
                provider.getId(),
                appointmentType.getId(),
                appointment.getId(),
                startAt,
                startAt.plusSeconds(3600));
    }

    private WaitlistEntry saveWaitlistEntry(
            CancellationFixture fixture, Long currentAppointmentId) {
        LocalDate appointmentDate = fixture.startAt().atZone(ZoneOffset.UTC).toLocalDate();
        WaitlistEntry waitlistEntry = new WaitlistEntry();
        waitlistEntry.setPatientId(fixture.patientId());
        waitlistEntry.setCurrentAppointmentId(currentAppointmentId);
        waitlistEntry.setAppointmentTypeId(fixture.appointmentTypeId());
        waitlistEntry.setEarliestAppointmentDate(appointmentDate);
        waitlistEntry.setLatestAppointmentDate(appointmentDate.plusDays(14));
        waitlistEntry.setPreferredTimeOfDay(TimeOfDayPreference.ANY);
        waitlistEntry.setStatus(WaitlistEntryStatus.ACTIVE);
        return waitlistEntryRepository.saveAndFlush(waitlistEntry);
    }

    private SlotOffer saveSlotOffer(
            CancellationFixture fixture, Long waitlistEntryId, SlotOfferStatus status) {
        long sequence = UNIQUE_SEQUENCE.incrementAndGet();
        Instant sourceStart = Instant.parse("2070-01-01T00:00:00Z")
                .plusSeconds(sequence * 7200);
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
        slotOffer.setWaitlistEntryId(waitlistEntryId);
        slotOffer.setStatus(status);
        slotOffer.setExpiresAt(Instant.parse("2090-01-01T00:00:00Z"));
        return slotOfferRepository.saveAndFlush(slotOffer);
    }

    private ProviderUnavailability saveBlock(
            CancellationFixture fixture, ProviderUnavailabilityStatus status) {
        ProviderUnavailability block = new ProviderUnavailability();
        block.setProviderId(fixture.providerId());
        block.setStartAt(fixture.startAt().plusSeconds(600));
        block.setEndAt(fixture.endAt().minusSeconds(600));
        block.setStatus(status);
        block.setReason("Cancellation test block");
        if (status == ProviderUnavailabilityStatus.ACTIVE) {
            block.setActivatedAt(Instant.now());
        }
        return providerUnavailabilityRepository.saveAndFlush(block);
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
        specialty.setName(uniqueValue("Cancellation Specialty"));
        specialty.setDescription("Appointment cancellation test specialty");
        return specialtyRepository.saveAndFlush(specialty);
    }

    private AppointmentType saveAppointmentType(Long specialtyId) {
        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName(uniqueValue("Cancellation Appointment Type"));
        appointmentType.setDurationMinutes(60);
        appointmentType.setSpecialtyId(specialtyId);
        appointmentType.setDescription("Appointment cancellation test type");
        appointmentType.setActive(true);
        return appointmentTypeRepository.saveAndFlush(appointmentType);
    }

    private Provider saveProvider(Long specialtyId) {
        User providerUser = saveUser(UserRole.PROVIDER);
        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(specialtyId);
        provider.setLicenseNumber(uniqueValue("CANCEL-LICENSE"));
        provider.setQualifications("Appointment cancellation test provider");
        return providerRepository.saveAndFlush(provider);
    }

    private User saveUser(UserRole role) {
        User user = new User();
        user.setEmail(uniqueValue(role.name().toLowerCase()) + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName("Cancellation test " + role.name().toLowerCase());
        return userRepository.saveAndFlush(user);
    }

    private WaitlistEntry reloadedEntry(WaitlistEntry waitlistEntry) {
        return waitlistEntryRepository.findById(waitlistEntry.getId()).orElseThrow();
    }

    private SlotOffer reloadedOffer(SlotOffer slotOffer) {
        return slotOfferRepository.findById(slotOffer.getId()).orElseThrow();
    }

    private RecoveryJob recoveryJobFor(Long appointmentId) {
        List<RecoveryJob> jobs = recoveryJobsFor(appointmentId);
        assertEquals(1, jobs.size());
        return jobs.getFirst();
    }

    private List<RecoveryJob> recoveryJobsFor(Long appointmentId) {
        return recoveryJobRepository.findAll().stream()
                .filter(job -> appointmentId.equals(job.getSourceAppointmentId()))
                .toList();
    }

    private List<AuditLog> operationAudits(
            Long appointmentId, List<Long> waitlistEntryIds, List<Long> slotOfferIds) {
        List<Long> recoveryJobIds = recoveryJobsFor(appointmentId).stream()
                .map(RecoveryJob::getId)
                .toList();
        List<AuditLog> result = new ArrayList<>();
        for (AuditLog audit : auditLogRepository.findAll()) {
            if (("Appointment".equals(audit.getEntityType())
                            && appointmentId.equals(audit.getEntityId()))
                    || ("WaitlistEntry".equals(audit.getEntityType())
                            && waitlistEntryIds.contains(audit.getEntityId()))
                    || ("SlotOffer".equals(audit.getEntityType())
                            && slotOfferIds.contains(audit.getEntityId()))
                    || ("RecoveryJob".equals(audit.getEntityType())
                            && recoveryJobIds.contains(audit.getEntityId()))) {
                result.add(audit);
            }
        }
        return List.copyOf(result);
    }

    private long matchingAudits(
            List<AuditLog> audits, String entityType, Long entityId, String action) {
        return audits.stream()
                .filter(log -> entityType.equals(log.getEntityType()))
                .filter(log -> entityId.equals(log.getEntityId()))
                .filter(log -> action.equals(log.getAction()))
                .count();
    }

    private static String uniqueValue(String prefix) {
        return prefix + "-" + UNIQUE_SEQUENCE.incrementAndGet();
    }

    private record CancellationFixture(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Long appointmentId,
            Instant startAt,
            Instant endAt) {
    }
}

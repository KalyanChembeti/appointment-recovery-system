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
import com.recoverysystem.exception.ProviderBlockConflictsUnresolvedException;
import com.recoverysystem.exception.ProviderBlockNotPendingException;
import com.recoverysystem.exception.ProviderUnavailabilityNotFoundException;
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
import com.recoverysystem.service.ProviderBlockActivationService;
import com.recoverysystem.service.ProviderBlockCreationService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
class ProviderBlockActivationServiceTest {

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
    private ProviderBlockActivationService providerBlockActivationService;

    @Autowired
    private ProviderBlockCreationService providerBlockCreationService;

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
    private ProviderUnavailabilityRepository providerUnavailabilityRepository;

    @Autowired
    private RecoveryJobRepository recoveryJobRepository;

    @Autowired
    private WaitlistEntryRepository waitlistEntryRepository;

    @Autowired
    private SlotOfferRepository slotOfferRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void resolvedConflictActivatesPendingBlockWithOneNewAudit() {
        PendingFixture fixture = createPendingFixture(
                Instant.parse("2043-01-10T14:00:00Z"), false);
        resolveConflict(fixture.conflictingAppointmentId());
        long auditCountBefore = auditLogRepository.count();

        ProviderUnavailability activated = providerBlockActivationService.activateProviderBlock(
                fixture.blockId(), fixture.provider().patientId());

        assertEquals(auditCountBefore + 1, auditLogRepository.count());
        assertEquals(ProviderUnavailabilityStatus.ACTIVE, activated.getStatus());
        assertNotNull(activated.getActivatedAt());
        assertEquals(0, activated.getActivatedAt().getNano() % 1_000);
        ProviderUnavailability reloaded =
                providerUnavailabilityRepository.findById(fixture.blockId()).orElseThrow();
        assertEquals(ProviderUnavailabilityStatus.ACTIVE, reloaded.getStatus());
        assertNotNull(reloaded.getActivatedAt());

        List<AuditLog> activationAudits = auditLogRepository.findAll().stream()
                .filter(log -> "ProviderUnavailability".equals(log.getEntityType()))
                .filter(log -> fixture.blockId().equals(log.getEntityId()))
                .filter(log -> "ACTIVATE".equals(log.getAction()))
                .toList();
        assertEquals(1, activationAudits.size());
        AuditLog activationAudit = activationAudits.getFirst();
        assertNull(activationAudit.getReason());
        assertEquals(ActorType.USER, activationAudit.getActorType());
        assertEquals(fixture.provider().patientId(), activationAudit.getActorUserId());
    }

    @Test
    void resolvedConflictActivationSuppressesJobAndCancelsOfferWithThreeNewAudits() {
        PendingFixture fixture = createPendingFixture(
                Instant.parse("2043-02-10T14:00:00Z"), true);
        resolveConflict(fixture.conflictingAppointmentId());
        long auditCountBefore = auditLogRepository.count();

        providerBlockActivationService.activateProviderBlock(
                fixture.blockId(), fixture.provider().patientId());

        assertEquals(auditCountBefore + 3, auditLogRepository.count());
        RecoveryJob recoveryJob =
                recoveryJobRepository.findById(fixture.recovery().jobId()).orElseThrow();
        assertEquals(RecoveryJobStatus.SUPPRESSED, recoveryJob.getStatus());
        assertEquals("PROVIDER_BLOCK_ACTIVATED", recoveryJob.getSuppressionReason());
        SlotOffer slotOffer =
                slotOfferRepository.findById(fixture.recovery().offerId()).orElseThrow();
        assertEquals(SlotOfferStatus.CANCELLED, slotOffer.getStatus());

        List<AuditLog> operationAudits = activationOperationAudits(fixture);
        assertEquals(3, operationAudits.size());
        assertNotNull(findAudit(
                operationAudits, "ProviderUnavailability", fixture.blockId(), "ACTIVATE"));
        assertNotNull(findAudit(
                operationAudits, "RecoveryJob", fixture.recovery().jobId(), "SUPPRESS"));
        AuditLog cancelAudit = findAudit(
                operationAudits,
                "SlotOffer",
                fixture.recovery().offerId(),
                "CANCEL");
        assertEquals("RECOVERY_JOB_SUPPRESSED", cancelAudit.getReason());
    }

    @Test
    void unresolvedConflictRejectsActivationWithoutChangesOrNewAudits() {
        PendingFixture fixture = createPendingFixture(
                Instant.parse("2043-03-10T14:00:00Z"), true);
        ProviderUnavailability blockBefore =
                providerUnavailabilityRepository.findById(fixture.blockId()).orElseThrow();
        Instant blockUpdatedAt = blockBefore.getUpdatedAt();
        RecoveryJob jobBefore =
                recoveryJobRepository.findById(fixture.recovery().jobId()).orElseThrow();
        Instant jobUpdatedAt = jobBefore.getUpdatedAt();
        SlotOffer offerBefore =
                slotOfferRepository.findById(fixture.recovery().offerId()).orElseThrow();
        Instant offerUpdatedAt = offerBefore.getUpdatedAt();
        long auditCountBefore = auditLogRepository.count();

        ProviderBlockConflictsUnresolvedException exception = assertThrows(
                ProviderBlockConflictsUnresolvedException.class,
                () -> providerBlockActivationService.activateProviderBlock(
                        fixture.blockId(), fixture.provider().patientId()));

        assertTrue(exception.getMessage().contains(fixture.blockId().toString()));
        assertTrue(exception.getMessage().contains(fixture.provider().providerId().toString()));
        assertTrue(exception.getMessage()
                .contains(fixture.conflictingAppointmentId().toString()));
        assertEquals(auditCountBefore, auditLogRepository.count());
        ProviderUnavailability blockAfter =
                providerUnavailabilityRepository.findById(fixture.blockId()).orElseThrow();
        assertEquals(ProviderUnavailabilityStatus.PENDING, blockAfter.getStatus());
        assertNull(blockAfter.getActivatedAt());
        assertEquals(blockUpdatedAt, blockAfter.getUpdatedAt());
        RecoveryJob jobAfter =
                recoveryJobRepository.findById(fixture.recovery().jobId()).orElseThrow();
        assertEquals(RecoveryJobStatus.OPEN, jobAfter.getStatus());
        assertNull(jobAfter.getSuppressionReason());
        assertEquals(jobUpdatedAt, jobAfter.getUpdatedAt());
        SlotOffer offerAfter =
                slotOfferRepository.findById(fixture.recovery().offerId()).orElseThrow();
        assertEquals(SlotOfferStatus.OFFERED, offerAfter.getStatus());
        assertEquals(offerUpdatedAt, offerAfter.getUpdatedAt());
    }

    @Test
    void nonexistentBlockIsRejected() {
        long auditCountBefore = auditLogRepository.count();

        assertThrows(
                ProviderUnavailabilityNotFoundException.class,
                () -> providerBlockActivationService.activateProviderBlock(
                        Long.MAX_VALUE, null));

        assertEquals(auditCountBefore, auditLogRepository.count());
    }

    @Test
    void alreadyActiveBlockIsRejectedWithoutChanges() {
        ProviderFixture provider = createProviderFixture();
        Instant blockStart = Instant.parse("2043-05-10T14:00:00Z");
        ProviderUnavailability activeBlock = providerBlockCreationService.createProviderBlock(
                provider.providerId(),
                blockStart,
                blockStart.plusSeconds(3600),
                "Active block",
                provider.patientId());
        ProviderUnavailability blockBefore =
                providerUnavailabilityRepository.findById(activeBlock.getId()).orElseThrow();
        Instant activatedAt = blockBefore.getActivatedAt();
        Instant updatedAt = blockBefore.getUpdatedAt();
        long auditCountBefore = auditLogRepository.count();

        ProviderBlockNotPendingException exception = assertThrows(
                ProviderBlockNotPendingException.class,
                () -> providerBlockActivationService.activateProviderBlock(
                        activeBlock.getId(), provider.patientId()));

        assertTrue(exception.getMessage().contains("ACTIVE"));
        assertEquals(auditCountBefore, auditLogRepository.count());
        ProviderUnavailability blockAfter =
                providerUnavailabilityRepository.findById(activeBlock.getId()).orElseThrow();
        assertEquals(ProviderUnavailabilityStatus.ACTIVE, blockAfter.getStatus());
        assertEquals(activatedAt, blockAfter.getActivatedAt());
        assertEquals(updatedAt, blockAfter.getUpdatedAt());
    }

    @Test
    void cancelledBlockIsRejectedAsNotPendingWithoutChanges() {
        ProviderFixture provider = createProviderFixture();
        Instant blockStart = Instant.parse("2043-06-10T14:00:00Z");
        ProviderUnavailability cancelledBlock = new ProviderUnavailability();
        cancelledBlock.setProviderId(provider.providerId());
        cancelledBlock.setStartAt(blockStart);
        cancelledBlock.setEndAt(blockStart.plusSeconds(3600));
        cancelledBlock.setStatus(ProviderUnavailabilityStatus.CANCELLED);
        cancelledBlock.setReason("Cancelled fixture");
        cancelledBlock.setCancelledAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        cancelledBlock = providerUnavailabilityRepository.saveAndFlush(cancelledBlock);
        Instant cancelledAt = cancelledBlock.getCancelledAt();
        Instant updatedAt = cancelledBlock.getUpdatedAt();
        long auditCountBefore = auditLogRepository.count();
        Long cancelledBlockId = cancelledBlock.getId();

        ProviderBlockNotPendingException exception = assertThrows(
                ProviderBlockNotPendingException.class,
                () -> providerBlockActivationService.activateProviderBlock(
                        cancelledBlockId, provider.patientId()));

        assertTrue(exception.getMessage().contains("CANCELLED"));
        assertEquals(auditCountBefore, auditLogRepository.count());
        ProviderUnavailability blockAfter =
                providerUnavailabilityRepository.findById(cancelledBlockId).orElseThrow();
        assertEquals(ProviderUnavailabilityStatus.CANCELLED, blockAfter.getStatus());
        assertNull(blockAfter.getActivatedAt());
        assertEquals(cancelledAt, blockAfter.getCancelledAt());
        assertEquals(updatedAt, blockAfter.getUpdatedAt());
    }

    @Test
    void actorAttributionAppliesToActivationAndCascadeAudits() {
        PendingFixture systemFixture = createPendingFixture(
                Instant.parse("2043-07-10T14:00:00Z"), true);
        resolveConflict(systemFixture.conflictingAppointmentId());
        Set<Long> systemAuditIdsBefore = auditIds();

        providerBlockActivationService.activateProviderBlock(systemFixture.blockId(), null);

        List<AuditLog> systemAudits = newAuditsSince(systemAuditIdsBefore);
        assertEquals(3, systemAudits.size());
        assertTrue(systemAudits.stream()
                .allMatch(audit -> audit.getActorType() == ActorType.SYSTEM));
        assertTrue(systemAudits.stream().allMatch(audit -> audit.getActorUserId() == null));

        PendingFixture userFixture = createPendingFixture(
                Instant.parse("2043-08-10T14:00:00Z"), true);
        resolveConflict(userFixture.conflictingAppointmentId());
        User actor = saveUser(UserRole.RECEPTIONIST);
        Set<Long> userAuditIdsBefore = auditIds();

        providerBlockActivationService.activateProviderBlock(
                userFixture.blockId(), actor.getId());

        List<AuditLog> userAudits = newAuditsSince(userAuditIdsBefore);
        assertEquals(3, userAudits.size());
        assertTrue(userAudits.stream()
                .allMatch(audit -> audit.getActorType() == ActorType.USER));
        assertTrue(userAudits.stream()
                .allMatch(audit -> actor.getId().equals(audit.getActorUserId())));
    }

    private PendingFixture createPendingFixture(Instant blockStart, boolean withRecovery) {
        ProviderFixture provider = createProviderFixture();
        Appointment conflictingAppointment = saveAppointment(
                provider,
                blockStart.plusSeconds(300),
                blockStart.plusSeconds(1200),
                AppointmentStatus.SCHEDULED);
        RecoveryFixture recovery = withRecovery
                ? saveRecoveryFixture(
                        provider,
                        blockStart.plusSeconds(600),
                        blockStart.plusSeconds(1800))
                : null;
        ProviderUnavailability pendingBlock = providerBlockCreationService.createProviderBlock(
                provider.providerId(),
                blockStart,
                blockStart.plusSeconds(3600),
                "Pending activation fixture",
                provider.patientId());
        assertEquals(ProviderUnavailabilityStatus.PENDING, pendingBlock.getStatus());
        return new PendingFixture(
                provider,
                pendingBlock.getId(),
                conflictingAppointment.getId(),
                recovery);
    }

    private void resolveConflict(Long conflictingAppointmentId) {
        Appointment appointment =
                appointmentRepository.findById(conflictingAppointmentId).orElseThrow();
        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setCancellationReason(CancellationReason.PATIENT_CANCELLED);
        appointmentRepository.saveAndFlush(appointment);
    }

    private ProviderFixture createProviderFixture() {
        Specialty specialty = saveSpecialty();
        AppointmentType appointmentType = saveAppointmentType(specialty.getId());
        Provider provider = saveProvider(specialty.getId());
        User patient = saveUser(UserRole.PATIENT);
        return new ProviderFixture(provider.getId(), appointmentType.getId(), patient.getId());
    }

    private RecoveryFixture saveRecoveryFixture(
            ProviderFixture provider, Instant sourceStart, Instant sourceEnd) {
        Appointment sourceAppointment = saveAppointment(
                provider, sourceStart, sourceEnd, AppointmentStatus.CANCELLED);
        RecoveryJob recoveryJob = new RecoveryJob();
        recoveryJob.setSourceAppointmentId(sourceAppointment.getId());
        recoveryJob.setStatus(RecoveryJobStatus.OPEN);
        RecoveryJob savedJob = recoveryJobRepository.saveAndFlush(recoveryJob);

        long sequence = UNIQUE_SEQUENCE.incrementAndGet();
        Instant anchorStart =
                Instant.parse("2071-01-01T14:00:00Z").plusSeconds(sequence * 7200);
        Appointment anchorAppointment = saveAppointment(
                provider,
                anchorStart,
                anchorStart.plusSeconds(3600),
                AppointmentStatus.SCHEDULED);
        WaitlistEntry waitlistEntry = saveWaitlistEntry(provider, anchorAppointment);

        SlotOffer slotOffer = new SlotOffer();
        slotOffer.setRecoveryJobId(savedJob.getId());
        slotOffer.setWaitlistEntryId(waitlistEntry.getId());
        slotOffer.setStatus(SlotOfferStatus.OFFERED);
        slotOffer.setExpiresAt(Instant.parse("2090-01-01T00:00:00Z"));
        SlotOffer savedOffer = slotOfferRepository.saveAndFlush(slotOffer);
        return new RecoveryFixture(savedJob.getId(), savedOffer.getId());
    }

    private Appointment saveAppointment(
            ProviderFixture provider,
            Instant startAt,
            Instant endAt,
            AppointmentStatus status) {
        Appointment appointment = new Appointment();
        appointment.setPatientId(provider.patientId());
        appointment.setProviderId(provider.providerId());
        appointment.setAppointmentTypeId(provider.appointmentTypeId());
        appointment.setStartAt(startAt);
        appointment.setEndAt(endAt);
        appointment.setStatus(status);
        if (status == AppointmentStatus.CANCELLED) {
            appointment.setCancellationReason(CancellationReason.PATIENT_CANCELLED);
        }
        return appointmentRepository.saveAndFlush(appointment);
    }

    private WaitlistEntry saveWaitlistEntry(
            ProviderFixture provider, Appointment anchorAppointment) {
        LocalDate anchorDate = anchorAppointment.getStartAt().atZone(ZoneOffset.UTC).toLocalDate();
        WaitlistEntry waitlistEntry = new WaitlistEntry();
        waitlistEntry.setPatientId(provider.patientId());
        waitlistEntry.setCurrentAppointmentId(anchorAppointment.getId());
        waitlistEntry.setAppointmentTypeId(provider.appointmentTypeId());
        waitlistEntry.setEarliestAppointmentDate(anchorDate);
        waitlistEntry.setLatestAppointmentDate(anchorDate.plusDays(14));
        waitlistEntry.setPreferredTimeOfDay(TimeOfDayPreference.ANY);
        waitlistEntry.setStatus(WaitlistEntryStatus.ACTIVE);
        return waitlistEntryRepository.saveAndFlush(waitlistEntry);
    }

    private Specialty saveSpecialty() {
        Specialty specialty = new Specialty();
        specialty.setName(uniqueValue("Activation Specialty"));
        specialty.setDescription("Provider block activation test specialty");
        return specialtyRepository.saveAndFlush(specialty);
    }

    private AppointmentType saveAppointmentType(Long specialtyId) {
        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName(uniqueValue("Activation Appointment Type"));
        appointmentType.setDurationMinutes(60);
        appointmentType.setSpecialtyId(specialtyId);
        appointmentType.setDescription("Provider block activation test appointment type");
        appointmentType.setActive(true);
        return appointmentTypeRepository.saveAndFlush(appointmentType);
    }

    private Provider saveProvider(Long specialtyId) {
        User providerUser = saveUser(UserRole.PROVIDER);
        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(specialtyId);
        provider.setLicenseNumber(uniqueValue("ACTIVATION-LICENSE"));
        provider.setQualifications("Provider block activation test provider");
        return providerRepository.saveAndFlush(provider);
    }

    private User saveUser(UserRole role) {
        User user = new User();
        user.setEmail(uniqueValue(role.name().toLowerCase()) + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName("Provider block activation " + role.name().toLowerCase());
        return userRepository.saveAndFlush(user);
    }

    private List<AuditLog> activationOperationAudits(PendingFixture fixture) {
        return auditLogRepository.findAll().stream()
                .filter(log ->
                        ("ProviderUnavailability".equals(log.getEntityType())
                                        && fixture.blockId().equals(log.getEntityId())
                                        && "ACTIVATE".equals(log.getAction()))
                                || ("RecoveryJob".equals(log.getEntityType())
                                        && fixture.recovery().jobId().equals(log.getEntityId()))
                                || ("SlotOffer".equals(log.getEntityType())
                                        && fixture.recovery().offerId().equals(log.getEntityId())))
                .toList();
    }

    private AuditLog findAudit(
            List<AuditLog> auditLogs, String entityType, Long entityId, String action) {
        return auditLogs.stream()
                .filter(log -> entityType.equals(log.getEntityType()))
                .filter(log -> entityId.equals(log.getEntityId()))
                .filter(log -> action.equals(log.getAction()))
                .findFirst()
                .orElseThrow();
    }

    private Set<Long> auditIds() {
        Set<Long> ids = new HashSet<>();
        auditLogRepository.findAll().forEach(audit -> ids.add(audit.getId()));
        return ids;
    }

    private List<AuditLog> newAuditsSince(Set<Long> priorAuditIds) {
        return auditLogRepository.findAll().stream()
                .filter(audit -> !priorAuditIds.contains(audit.getId()))
                .toList();
    }

    private static String uniqueValue(String prefix) {
        return prefix + "-" + UNIQUE_SEQUENCE.incrementAndGet();
    }

    private record ProviderFixture(Long providerId, Long appointmentTypeId, Long patientId) {
    }

    private record RecoveryFixture(Long jobId, Long offerId) {
    }

    private record PendingFixture(
            ProviderFixture provider,
            Long blockId,
            Long conflictingAppointmentId,
            RecoveryFixture recovery) {
    }
}

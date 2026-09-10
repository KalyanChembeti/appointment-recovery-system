package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.recoverysystem.exception.InvalidBlockIntervalException;
import com.recoverysystem.exception.ProviderNotFoundException;
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
import com.recoverysystem.service.ProviderBlockCreationService;
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
class ProviderBlockCreationServiceTest {

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

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void noConflictsAndNoRecoveryJobsCreatesActiveBlockWithOnlyCreateAudit() {
        ProviderFixture fixture = createProviderFixture();
        Instant blockStart = Instant.parse("2042-01-10T14:00:00Z");
        Instant blockEnd = blockStart.plusSeconds(3600);

        ProviderUnavailability block = providerBlockCreationService.createProviderBlock(
                fixture.providerId(),
                blockStart,
                blockEnd,
                "Conference",
                fixture.patientId());

        ProviderUnavailability reloaded =
                providerUnavailabilityRepository.findById(block.getId()).orElseThrow();
        assertEquals(ProviderUnavailabilityStatus.ACTIVE, reloaded.getStatus());
        assertEquals("Conference", reloaded.getReason());
        assertNotNull(reloaded.getActivatedAt());
        assertEquals(0, reloaded.getActivatedAt().getNano() % 1_000);

        List<AuditLog> auditLogs = operationAudits(block.getId(), List.of(), List.of());
        assertEquals(1, auditLogs.size());
        AuditLog createAudit = auditLogs.getFirst();
        assertEquals("ProviderUnavailability", createAudit.getEntityType());
        assertEquals("CREATE", createAudit.getAction());
        assertNull(createAudit.getReason());
        assertNull(createAudit.getOldValues());
        assertNull(createAudit.getNewValues());
    }

    @Test
    void activeBlockSuppressesAffectedOpenRecoveryJobAndCancelsOfferedSlot() {
        ProviderFixture fixture = createProviderFixture();
        Instant blockStart = Instant.parse("2042-02-10T14:00:00Z");
        Instant blockEnd = blockStart.plusSeconds(3600);
        RecoveryFixture recovery = saveRecoveryFixture(
                fixture,
                blockStart.plusSeconds(600),
                blockStart.plusSeconds(1800),
                RecoveryJobStatus.OPEN,
                List.of(SlotOfferStatus.OFFERED));

        ProviderUnavailability block = providerBlockCreationService.createProviderBlock(
                fixture.providerId(), blockStart, blockEnd, "Training", fixture.patientId());

        assertEquals(ProviderUnavailabilityStatus.ACTIVE, block.getStatus());
        RecoveryJob reloadedJob = recoveryJobRepository.findById(recovery.jobId()).orElseThrow();
        assertEquals(RecoveryJobStatus.SUPPRESSED, reloadedJob.getStatus());
        assertEquals("PROVIDER_BLOCK_ACTIVATED", reloadedJob.getSuppressionReason());
        SlotOffer reloadedOffer = slotOfferRepository
                .findById(recovery.offerIds().getFirst())
                .orElseThrow();
        assertEquals(SlotOfferStatus.CANCELLED, reloadedOffer.getStatus());

        List<AuditLog> auditLogs =
                operationAudits(block.getId(), List.of(recovery.jobId()), recovery.offerIds());
        assertEquals(3, auditLogs.size());
        AuditLog createAudit = findAudit(
                auditLogs, "ProviderUnavailability", block.getId(), "CREATE");
        assertNull(createAudit.getReason());
        AuditLog suppressAudit =
                findAudit(auditLogs, "RecoveryJob", recovery.jobId(), "SUPPRESS");
        assertNull(suppressAudit.getReason());
        AuditLog cancelAudit = findAudit(
                auditLogs,
                "SlotOffer",
                recovery.offerIds().getFirst(),
                "CANCEL");
        assertEquals("RECOVERY_JOB_SUPPRESSED", cancelAudit.getReason());
    }

    @Test
    void mixedSlotOfferStatusesCancelAndAuditOnlyOfferedSibling() {
        ProviderFixture fixture = createProviderFixture();
        Instant blockStart = Instant.parse("2042-03-10T14:00:00Z");
        Instant blockEnd = blockStart.plusSeconds(3600);
        RecoveryFixture recovery = saveRecoveryFixture(
                fixture,
                blockStart.plusSeconds(600),
                blockStart.plusSeconds(1800),
                RecoveryJobStatus.OPEN,
                List.of(SlotOfferStatus.OFFERED, SlotOfferStatus.DECLINED));
        Long offeredId = recovery.offerIds().get(0);
        Long declinedId = recovery.offerIds().get(1);
        Instant declinedUpdatedAt =
                slotOfferRepository.findById(declinedId).orElseThrow().getUpdatedAt();

        ProviderUnavailability block = providerBlockCreationService.createProviderBlock(
                fixture.providerId(), blockStart, blockEnd, "Procedure", fixture.patientId());

        assertEquals(
                SlotOfferStatus.CANCELLED,
                slotOfferRepository.findById(offeredId).orElseThrow().getStatus());
        SlotOffer declinedOffer = slotOfferRepository.findById(declinedId).orElseThrow();
        assertEquals(SlotOfferStatus.DECLINED, declinedOffer.getStatus());
        assertEquals(declinedUpdatedAt, declinedOffer.getUpdatedAt());

        List<AuditLog> auditLogs =
                operationAudits(block.getId(), List.of(recovery.jobId()), recovery.offerIds());
        assertEquals(3, auditLogs.size());
        assertEquals(
                1,
                auditLogs.stream()
                        .filter(log -> "SlotOffer".equals(log.getEntityType()))
                        .filter(log -> offeredId.equals(log.getEntityId()))
                        .filter(log -> "CANCEL".equals(log.getAction()))
                        .count());
        assertEquals(
                0,
                auditLogs.stream()
                        .filter(log -> "SlotOffer".equals(log.getEntityType()))
                        .filter(log -> declinedId.equals(log.getEntityId()))
                        .count());
    }

    @Test
    void nonOverlappingRecoveryJobRemainsOpenAndUnaudited() {
        ProviderFixture fixture = createProviderFixture();
        Instant blockStart = Instant.parse("2042-04-10T14:00:00Z");
        Instant blockEnd = blockStart.plusSeconds(3600);
        RecoveryFixture recovery = saveRecoveryFixture(
                fixture,
                blockStart.minusSeconds(3600),
                blockStart,
                RecoveryJobStatus.OPEN,
                List.of(SlotOfferStatus.OFFERED));
        RecoveryJob jobBefore = recoveryJobRepository.findById(recovery.jobId()).orElseThrow();
        Instant jobUpdatedAt = jobBefore.getUpdatedAt();
        Long offerId = recovery.offerIds().getFirst();
        Instant offerUpdatedAt =
                slotOfferRepository.findById(offerId).orElseThrow().getUpdatedAt();

        ProviderUnavailability block = providerBlockCreationService.createProviderBlock(
                fixture.providerId(), blockStart, blockEnd, "Clinic closure", null);

        RecoveryJob reloadedJob = recoveryJobRepository.findById(recovery.jobId()).orElseThrow();
        assertEquals(RecoveryJobStatus.OPEN, reloadedJob.getStatus());
        assertNull(reloadedJob.getSuppressionReason());
        assertEquals(jobUpdatedAt, reloadedJob.getUpdatedAt());
        SlotOffer reloadedOffer = slotOfferRepository.findById(offerId).orElseThrow();
        assertEquals(SlotOfferStatus.OFFERED, reloadedOffer.getStatus());
        assertEquals(offerUpdatedAt, reloadedOffer.getUpdatedAt());
        assertEquals(
                1,
                operationAudits(block.getId(), List.of(recovery.jobId()), recovery.offerIds())
                        .size());
    }

    @Test
    void alreadySuppressedOverlappingRecoveryJobIsNotTouchedOrAuditedAgain() {
        ProviderFixture fixture = createProviderFixture();
        Instant blockStart = Instant.parse("2042-05-10T14:00:00Z");
        Instant blockEnd = blockStart.plusSeconds(3600);
        RecoveryFixture recovery = saveRecoveryFixture(
                fixture,
                blockStart.plusSeconds(600),
                blockStart.plusSeconds(1800),
                RecoveryJobStatus.SUPPRESSED,
                List.of(SlotOfferStatus.OFFERED));
        RecoveryJob jobBefore = recoveryJobRepository.findById(recovery.jobId()).orElseThrow();
        Instant jobUpdatedAt = jobBefore.getUpdatedAt();
        Long offerId = recovery.offerIds().getFirst();
        Instant offerUpdatedAt =
                slotOfferRepository.findById(offerId).orElseThrow().getUpdatedAt();

        ProviderUnavailability block = providerBlockCreationService.createProviderBlock(
                fixture.providerId(), blockStart, blockEnd, "Maintenance", fixture.patientId());

        RecoveryJob reloadedJob = recoveryJobRepository.findById(recovery.jobId()).orElseThrow();
        assertEquals(RecoveryJobStatus.SUPPRESSED, reloadedJob.getStatus());
        assertEquals("EXISTING_SUPPRESSION", reloadedJob.getSuppressionReason());
        assertEquals(jobUpdatedAt, reloadedJob.getUpdatedAt());
        SlotOffer reloadedOffer = slotOfferRepository.findById(offerId).orElseThrow();
        assertEquals(SlotOfferStatus.OFFERED, reloadedOffer.getStatus());
        assertEquals(offerUpdatedAt, reloadedOffer.getUpdatedAt());
        assertEquals(
                1,
                operationAudits(block.getId(), List.of(recovery.jobId()), recovery.offerIds())
                        .size());
    }

    @Test
    void scheduledConflictCreatesPendingBlockAndLeavesRecoveryStateUntouched() throws Exception {
        ProviderFixture fixture = createProviderFixture();
        Instant blockStart = Instant.parse("2042-06-10T14:00:00Z");
        Instant blockEnd = blockStart.plusSeconds(3600);
        Appointment conflictingAppointment = saveAppointment(
                fixture,
                blockStart.plusSeconds(300),
                blockStart.plusSeconds(1200),
                AppointmentStatus.SCHEDULED);
        RecoveryFixture recovery = saveRecoveryFixture(
                fixture,
                blockStart.plusSeconds(600),
                blockStart.plusSeconds(1800),
                RecoveryJobStatus.OPEN,
                List.of(SlotOfferStatus.OFFERED));
        RecoveryJob jobBefore = recoveryJobRepository.findById(recovery.jobId()).orElseThrow();
        Instant jobUpdatedAt = jobBefore.getUpdatedAt();
        Long offerId = recovery.offerIds().getFirst();
        Instant offerUpdatedAt =
                slotOfferRepository.findById(offerId).orElseThrow().getUpdatedAt();

        ProviderUnavailability block = providerBlockCreationService.createProviderBlock(
                fixture.providerId(),
                blockStart,
                blockEnd,
                "Medical leave",
                fixture.patientId());

        assertEquals(ProviderUnavailabilityStatus.PENDING, block.getStatus());
        assertEquals("Medical leave", block.getReason());
        assertNull(block.getActivatedAt());
        RecoveryJob reloadedJob = recoveryJobRepository.findById(recovery.jobId()).orElseThrow();
        assertEquals(RecoveryJobStatus.OPEN, reloadedJob.getStatus());
        assertNull(reloadedJob.getSuppressionReason());
        assertEquals(jobUpdatedAt, reloadedJob.getUpdatedAt());
        SlotOffer reloadedOffer = slotOfferRepository.findById(offerId).orElseThrow();
        assertEquals(SlotOfferStatus.OFFERED, reloadedOffer.getStatus());
        assertEquals(offerUpdatedAt, reloadedOffer.getUpdatedAt());

        List<AuditLog> auditLogs =
                operationAudits(block.getId(), List.of(recovery.jobId()), recovery.offerIds());
        assertEquals(1, auditLogs.size());
        AuditLog createAudit = auditLogs.getFirst();
        assertEquals("ProviderUnavailability", createAudit.getEntityType());
        assertEquals("CREATE", createAudit.getAction());
        assertEquals("AWAITING_CONFLICT_RESOLUTION", createAudit.getReason());
        assertNull(createAudit.getOldValues());
        JsonNode newValues = objectMapper.readTree(createAudit.getNewValues());
        assertEquals(1, newValues.size());
        JsonNode conflictingIds = newValues.get("conflictingAppointmentIds");
        assertEquals(1, conflictingIds.size());
        assertEquals(conflictingAppointment.getId().longValue(), conflictingIds.get(0).asLong());
    }

    @Test
    void nonexistentProviderIsRejected() {
        long blockCountBefore = providerUnavailabilityRepository.count();
        long auditCountBefore = auditLogRepository.count();

        assertThrows(
                ProviderNotFoundException.class,
                () -> providerBlockCreationService.createProviderBlock(
                        Long.MAX_VALUE,
                        Instant.parse("2042-07-10T14:00:00Z"),
                        Instant.parse("2042-07-10T15:00:00Z"),
                        "Unknown provider",
                        null));

        assertEquals(blockCountBefore, providerUnavailabilityRepository.count());
        assertEquals(auditCountBefore, auditLogRepository.count());
    }

    @Test
    void nonIncreasingBlockIntervalIsRejected() {
        ProviderFixture fixture = createProviderFixture();
        Instant startAt = Instant.parse("2042-08-10T14:00:00Z");
        long blockCountBefore = providerUnavailabilityRepository.count();

        assertThrows(
                InvalidBlockIntervalException.class,
                () -> providerBlockCreationService.createProviderBlock(
                        fixture.providerId(),
                        startAt,
                        startAt,
                        "Zero duration",
                        fixture.patientId()));
        assertThrows(
                InvalidBlockIntervalException.class,
                () -> providerBlockCreationService.createProviderBlock(
                        fixture.providerId(),
                        startAt,
                        startAt.minusSeconds(1),
                        "Reversed interval",
                        fixture.patientId()));

        assertEquals(blockCountBefore, providerUnavailabilityRepository.count());
    }

    @Test
    void auditActorUsesSystemForNullAndUserForNonNullAcrossEachCascade() {
        Instant systemBlockStart = Instant.parse("2042-09-10T14:00:00Z");
        ProviderFixture systemFixture = createProviderFixture();
        RecoveryFixture systemRecovery = saveRecoveryFixture(
                systemFixture,
                systemBlockStart.plusSeconds(600),
                systemBlockStart.plusSeconds(1800),
                RecoveryJobStatus.OPEN,
                List.of(SlotOfferStatus.OFFERED));
        ProviderUnavailability systemBlock = providerBlockCreationService.createProviderBlock(
                systemFixture.providerId(),
                systemBlockStart,
                systemBlockStart.plusSeconds(3600),
                "System block",
                null);

        Instant userBlockStart = Instant.parse("2042-10-10T14:00:00Z");
        ProviderFixture userFixture = createProviderFixture();
        RecoveryFixture userRecovery = saveRecoveryFixture(
                userFixture,
                userBlockStart.plusSeconds(600),
                userBlockStart.plusSeconds(1800),
                RecoveryJobStatus.OPEN,
                List.of(SlotOfferStatus.OFFERED));
        User actor = saveUser(UserRole.RECEPTIONIST);
        ProviderUnavailability userBlock = providerBlockCreationService.createProviderBlock(
                userFixture.providerId(),
                userBlockStart,
                userBlockStart.plusSeconds(3600),
                "User block",
                actor.getId());

        List<AuditLog> systemAudits = operationAudits(
                systemBlock.getId(),
                List.of(systemRecovery.jobId()),
                systemRecovery.offerIds());
        assertEquals(3, systemAudits.size());
        assertTrue(systemAudits.stream()
                .allMatch(audit -> audit.getActorType() == ActorType.SYSTEM));
        assertTrue(systemAudits.stream().allMatch(audit -> audit.getActorUserId() == null));

        List<AuditLog> userAudits = operationAudits(
                userBlock.getId(), List.of(userRecovery.jobId()), userRecovery.offerIds());
        assertEquals(3, userAudits.size());
        assertTrue(userAudits.stream()
                .allMatch(audit -> audit.getActorType() == ActorType.USER));
        assertTrue(userAudits.stream()
                .allMatch(audit -> actor.getId().equals(audit.getActorUserId())));
    }

    private ProviderFixture createProviderFixture() {
        Specialty specialty = saveSpecialty();
        AppointmentType appointmentType = saveAppointmentType(specialty.getId());
        Provider provider = saveProvider(specialty.getId());
        User patient = saveUser(UserRole.PATIENT);
        return new ProviderFixture(
                provider.getId(), specialty.getId(), appointmentType.getId(), patient.getId());
    }

    private RecoveryFixture saveRecoveryFixture(
            ProviderFixture fixture,
            Instant sourceStart,
            Instant sourceEnd,
            RecoveryJobStatus jobStatus,
            List<SlotOfferStatus> offerStatuses) {
        Appointment sourceAppointment = saveAppointment(
                fixture, sourceStart, sourceEnd, AppointmentStatus.CANCELLED);

        RecoveryJob recoveryJob = new RecoveryJob();
        recoveryJob.setSourceAppointmentId(sourceAppointment.getId());
        recoveryJob.setStatus(jobStatus);
        if (jobStatus == RecoveryJobStatus.SUPPRESSED) {
            recoveryJob.setSuppressionReason("EXISTING_SUPPRESSION");
        }
        RecoveryJob savedJob = recoveryJobRepository.saveAndFlush(recoveryJob);

        if (offerStatuses.isEmpty()) {
            return new RecoveryFixture(savedJob.getId(), List.of());
        }

        long sequence = UNIQUE_SEQUENCE.incrementAndGet();
        Instant anchorStart =
                Instant.parse("2070-01-01T14:00:00Z").plusSeconds(sequence * 7200);
        Appointment anchorAppointment = saveAppointment(
                fixture,
                anchorStart,
                anchorStart.plusSeconds(3600),
                AppointmentStatus.SCHEDULED);
        WaitlistEntry waitlistEntry = saveWaitlistEntry(fixture, anchorAppointment);

        List<Long> offerIds = new ArrayList<>();
        for (SlotOfferStatus offerStatus : offerStatuses) {
            SlotOffer slotOffer = new SlotOffer();
            slotOffer.setRecoveryJobId(savedJob.getId());
            slotOffer.setWaitlistEntryId(waitlistEntry.getId());
            slotOffer.setStatus(offerStatus);
            slotOffer.setExpiresAt(Instant.parse("2090-01-01T00:00:00Z"));
            offerIds.add(slotOfferRepository.saveAndFlush(slotOffer).getId());
        }
        return new RecoveryFixture(savedJob.getId(), List.copyOf(offerIds));
    }

    private Appointment saveAppointment(
            ProviderFixture fixture,
            Instant startAt,
            Instant endAt,
            AppointmentStatus status) {
        Appointment appointment = new Appointment();
        appointment.setPatientId(fixture.patientId());
        appointment.setProviderId(fixture.providerId());
        appointment.setAppointmentTypeId(fixture.appointmentTypeId());
        appointment.setStartAt(startAt);
        appointment.setEndAt(endAt);
        appointment.setStatus(status);
        if (status == AppointmentStatus.CANCELLED) {
            appointment.setCancellationReason(CancellationReason.PATIENT_CANCELLED);
        }
        return appointmentRepository.saveAndFlush(appointment);
    }

    private WaitlistEntry saveWaitlistEntry(
            ProviderFixture fixture, Appointment anchorAppointment) {
        LocalDate anchorDate = anchorAppointment.getStartAt().atZone(ZoneOffset.UTC).toLocalDate();
        WaitlistEntry waitlistEntry = new WaitlistEntry();
        waitlistEntry.setPatientId(fixture.patientId());
        waitlistEntry.setCurrentAppointmentId(anchorAppointment.getId());
        waitlistEntry.setAppointmentTypeId(fixture.appointmentTypeId());
        waitlistEntry.setEarliestAppointmentDate(anchorDate);
        waitlistEntry.setLatestAppointmentDate(anchorDate.plusDays(14));
        waitlistEntry.setPreferredTimeOfDay(TimeOfDayPreference.ANY);
        waitlistEntry.setStatus(WaitlistEntryStatus.ACTIVE);
        return waitlistEntryRepository.saveAndFlush(waitlistEntry);
    }

    private Specialty saveSpecialty() {
        Specialty specialty = new Specialty();
        specialty.setName(uniqueValue("Block Specialty"));
        specialty.setDescription("Provider block creation test specialty");
        return specialtyRepository.saveAndFlush(specialty);
    }

    private AppointmentType saveAppointmentType(Long specialtyId) {
        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName(uniqueValue("Block Appointment Type"));
        appointmentType.setDurationMinutes(60);
        appointmentType.setSpecialtyId(specialtyId);
        appointmentType.setDescription("Provider block creation test appointment type");
        appointmentType.setActive(true);
        return appointmentTypeRepository.saveAndFlush(appointmentType);
    }

    private Provider saveProvider(Long specialtyId) {
        User providerUser = saveUser(UserRole.PROVIDER);
        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(specialtyId);
        provider.setLicenseNumber(uniqueValue("BLOCK-LICENSE"));
        provider.setQualifications("Provider block creation test provider");
        return providerRepository.saveAndFlush(provider);
    }

    private User saveUser(UserRole role) {
        User user = new User();
        user.setEmail(uniqueValue(role.name().toLowerCase()) + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName("Provider block test " + role.name().toLowerCase());
        return userRepository.saveAndFlush(user);
    }

    private List<AuditLog> operationAudits(
            Long blockId, List<Long> recoveryJobIds, List<Long> slotOfferIds) {
        return auditLogRepository.findAll().stream()
                .filter(log ->
                        ("ProviderUnavailability".equals(log.getEntityType())
                                        && blockId.equals(log.getEntityId()))
                                || ("RecoveryJob".equals(log.getEntityType())
                                        && recoveryJobIds.contains(log.getEntityId()))
                                || ("SlotOffer".equals(log.getEntityType())
                                        && slotOfferIds.contains(log.getEntityId())))
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

    private static String uniqueValue(String prefix) {
        return prefix + "-" + UNIQUE_SEQUENCE.incrementAndGet();
    }

    private record ProviderFixture(
            Long providerId, Long specialtyId, Long appointmentTypeId, Long patientId) {
    }

    private record RecoveryFixture(Long jobId, List<Long> offerIds) {
    }
}

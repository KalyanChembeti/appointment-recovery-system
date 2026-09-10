package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.recoverysystem.exception.SlotOfferNotFoundException;
import com.recoverysystem.exception.SlotOfferNotOfferedException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import com.recoverysystem.service.SlotOfferDeclineService;
import java.time.Instant;
import java.time.LocalDate;
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
class SlotOfferDeclineServiceTest {

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
    private SlotOfferDeclineService slotOfferDeclineService;

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
    void offeredSlotIsDeclinedWithOnePatientAudit() {
        OfferFixture fixture = createFixture(SlotOfferStatus.OFFERED);

        SlotOffer result =
                slotOfferDeclineService.declineOffer(fixture.slotOfferId(), fixture.patientId());

        assertEquals(SlotOfferStatus.DECLINED, result.getStatus());
        assertEquals(
                SlotOfferStatus.DECLINED,
                slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow().getStatus());
        List<AuditLog> audits = offerAudits(fixture.slotOfferId());
        assertEquals(1, audits.size());
        AuditLog audit = audits.getFirst();
        assertEquals("SlotOffer", audit.getEntityType());
        assertEquals(fixture.slotOfferId(), audit.getEntityId());
        assertEquals("DECLINE", audit.getAction());
        assertEquals(ActorType.USER, audit.getActorType());
        assertEquals(fixture.patientId(), audit.getActorUserId());
        assertNull(audit.getReason());
    }

    @Test
    void acceptedSlotIsRejectedWithoutChanges() {
        assertNonOfferedSlotIsRejectedWithoutChanges(SlotOfferStatus.ACCEPTED);
    }

    @Test
    void secondDeclineAttemptIsRejectedWithoutAdditionalChanges() {
        OfferFixture fixture = createFixture(SlotOfferStatus.OFFERED);
        slotOfferDeclineService.declineOffer(fixture.slotOfferId(), fixture.patientId());
        SlotOffer afterFirstDecline =
                slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        Instant updatedAt = afterFirstDecline.getUpdatedAt();

        SlotOfferNotOfferedException exception = assertThrows(
                SlotOfferNotOfferedException.class,
                () -> slotOfferDeclineService.declineOffer(
                        fixture.slotOfferId(), fixture.patientId()));

        assertTrue(exception.getMessage().contains("DECLINED"));
        SlotOffer reloaded = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        assertEquals(SlotOfferStatus.DECLINED, reloaded.getStatus());
        assertEquals(updatedAt, reloaded.getUpdatedAt());
        assertEquals(1, offerAudits(fixture.slotOfferId()).size());
    }

    @Test
    void expiredSlotIsRejectedWithoutChanges() {
        assertNonOfferedSlotIsRejectedWithoutChanges(SlotOfferStatus.EXPIRED);
    }

    @Test
    void cancelledSlotIsRejectedWithoutChanges() {
        assertNonOfferedSlotIsRejectedWithoutChanges(SlotOfferStatus.CANCELLED);
    }

    @Test
    void nonexistentSlotOfferIsRejected() {
        long auditCount = auditLogRepository.count();

        assertThrows(
                SlotOfferNotFoundException.class,
                () -> slotOfferDeclineService.declineOffer(Long.MAX_VALUE, Long.MAX_VALUE));

        assertEquals(auditCount, auditLogRepository.count());
    }

    @Test
    void successfulDeclineLeavesRecoveryJobOpenAndUntouched() {
        OfferFixture fixture = createFixture(SlotOfferStatus.OFFERED);
        RecoveryJob before = recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        Instant updatedAt = before.getUpdatedAt();

        slotOfferDeclineService.declineOffer(fixture.slotOfferId(), fixture.patientId());

        RecoveryJob reloaded =
                recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        assertEquals(RecoveryJobStatus.OPEN, reloaded.getStatus());
        assertNull(reloaded.getFilledAt());
        assertNull(reloaded.getSuppressionReason());
        assertEquals(updatedAt, reloaded.getUpdatedAt());
    }

    @Test
    void successfulDeclineLeavesWaitlistEntryActiveAndUntouched() {
        OfferFixture fixture = createFixture(SlotOfferStatus.OFFERED);
        WaitlistEntry before =
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow();
        Instant updatedAt = before.getUpdatedAt();

        slotOfferDeclineService.declineOffer(fixture.slotOfferId(), fixture.patientId());

        WaitlistEntry reloaded =
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow();
        assertEquals(WaitlistEntryStatus.ACTIVE, reloaded.getStatus());
        assertEquals(updatedAt, reloaded.getUpdatedAt());
    }

    private void assertNonOfferedSlotIsRejectedWithoutChanges(SlotOfferStatus status) {
        OfferFixture fixture = createFixture(status);
        SlotOffer before = slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        Instant offerUpdatedAt = before.getUpdatedAt();
        RecoveryJob recoveryJobBefore =
                recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        Instant recoveryUpdatedAt = recoveryJobBefore.getUpdatedAt();
        WaitlistEntry waitlistEntryBefore =
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow();
        Instant waitlistUpdatedAt = waitlistEntryBefore.getUpdatedAt();

        SlotOfferNotOfferedException exception = assertThrows(
                SlotOfferNotOfferedException.class,
                () -> slotOfferDeclineService.declineOffer(
                        fixture.slotOfferId(), fixture.patientId()));

        assertTrue(exception.getMessage().contains(status.name()));
        SlotOffer reloadedOffer =
                slotOfferRepository.findById(fixture.slotOfferId()).orElseThrow();
        assertEquals(status, reloadedOffer.getStatus());
        assertEquals(offerUpdatedAt, reloadedOffer.getUpdatedAt());
        RecoveryJob reloadedRecovery =
                recoveryJobRepository.findById(fixture.recoveryJobId()).orElseThrow();
        assertEquals(RecoveryJobStatus.OPEN, reloadedRecovery.getStatus());
        assertEquals(recoveryUpdatedAt, reloadedRecovery.getUpdatedAt());
        WaitlistEntry reloadedWaitlist =
                waitlistEntryRepository.findById(fixture.waitlistEntryId()).orElseThrow();
        assertEquals(WaitlistEntryStatus.ACTIVE, reloadedWaitlist.getStatus());
        assertEquals(waitlistUpdatedAt, reloadedWaitlist.getUpdatedAt());
        assertTrue(offerAudits(fixture.slotOfferId()).isEmpty());
    }

    private OfferFixture createFixture(SlotOfferStatus offerStatus) {
        Specialty specialty = new Specialty();
        specialty.setName(uniqueValue("Decline Specialty"));
        Specialty savedSpecialty = specialtyRepository.saveAndFlush(specialty);

        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName(uniqueValue("Decline Appointment Type"));
        appointmentType.setDurationMinutes(60);
        appointmentType.setSpecialtyId(savedSpecialty.getId());
        appointmentType.setActive(true);
        AppointmentType savedAppointmentType =
                appointmentTypeRepository.saveAndFlush(appointmentType);

        User providerUser = saveUser(UserRole.PROVIDER);
        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(savedSpecialty.getId());
        provider.setLicenseNumber(uniqueValue("DECLINE-LICENSE"));
        Provider savedProvider = providerRepository.saveAndFlush(provider);
        User patient = saveUser(UserRole.PATIENT);

        long sequence = UNIQUE_SEQUENCE.incrementAndGet();
        Instant anchorStart = Instant.parse("2060-01-01T14:00:00Z")
                .plusSeconds(sequence * 172800);
        Appointment anchorAppointment = saveAppointment(
                patient.getId(),
                savedProvider.getId(),
                savedAppointmentType.getId(),
                anchorStart,
                AppointmentStatus.SCHEDULED);

        LocalDate anchorDate = LocalDate.ofInstant(anchorStart, java.time.ZoneOffset.UTC);
        WaitlistEntry waitlistEntry = new WaitlistEntry();
        waitlistEntry.setPatientId(patient.getId());
        waitlistEntry.setCurrentAppointmentId(anchorAppointment.getId());
        waitlistEntry.setAppointmentTypeId(savedAppointmentType.getId());
        waitlistEntry.setEarliestAppointmentDate(anchorDate);
        waitlistEntry.setLatestAppointmentDate(anchorDate.plusDays(14));
        waitlistEntry.setPreferredTimeOfDay(TimeOfDayPreference.ANY);
        waitlistEntry.setStatus(WaitlistEntryStatus.ACTIVE);
        WaitlistEntry savedWaitlistEntry = waitlistEntryRepository.saveAndFlush(waitlistEntry);

        Instant sourceStart = Instant.parse("2080-01-01T14:00:00Z")
                .plusSeconds(sequence * 172800);
        Appointment sourceAppointment = saveAppointment(
                patient.getId(),
                savedProvider.getId(),
                savedAppointmentType.getId(),
                sourceStart,
                AppointmentStatus.CANCELLED);
        RecoveryJob recoveryJob = new RecoveryJob();
        recoveryJob.setSourceAppointmentId(sourceAppointment.getId());
        recoveryJob.setStatus(RecoveryJobStatus.OPEN);
        RecoveryJob savedRecoveryJob = recoveryJobRepository.saveAndFlush(recoveryJob);

        SlotOffer slotOffer = new SlotOffer();
        slotOffer.setRecoveryJobId(savedRecoveryJob.getId());
        slotOffer.setWaitlistEntryId(savedWaitlistEntry.getId());
        slotOffer.setStatus(offerStatus);
        slotOffer.setExpiresAt(Instant.parse("2090-01-01T00:00:00Z"));
        SlotOffer savedSlotOffer = slotOfferRepository.saveAndFlush(slotOffer);

        return new OfferFixture(
                patient.getId(),
                savedRecoveryJob.getId(),
                savedWaitlistEntry.getId(),
                savedSlotOffer.getId());
    }

    private Appointment saveAppointment(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Instant startAt,
            AppointmentStatus status) {
        Appointment appointment = new Appointment();
        appointment.setPatientId(patientId);
        appointment.setProviderId(providerId);
        appointment.setAppointmentTypeId(appointmentTypeId);
        appointment.setStartAt(startAt);
        appointment.setEndAt(startAt.plusSeconds(3600));
        appointment.setStatus(status);
        if (status == AppointmentStatus.CANCELLED) {
            appointment.setCancellationReason(CancellationReason.PATIENT_CANCELLED);
        }
        return appointmentRepository.saveAndFlush(appointment);
    }

    private User saveUser(UserRole role) {
        User user = new User();
        user.setEmail(uniqueValue(role.name().toLowerCase()) + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName("Slot offer decline " + role.name().toLowerCase());
        return userRepository.saveAndFlush(user);
    }

    private List<AuditLog> offerAudits(Long slotOfferId) {
        return auditLogRepository.findAll().stream()
                .filter(log -> "SlotOffer".equals(log.getEntityType()))
                .filter(log -> slotOfferId.equals(log.getEntityId()))
                .toList();
    }

    private static String uniqueValue(String prefix) {
        return prefix + "-" + UNIQUE_SEQUENCE.incrementAndGet();
    }

    private record OfferFixture(
            Long patientId, Long recoveryJobId, Long waitlistEntryId, Long slotOfferId) {
    }
}

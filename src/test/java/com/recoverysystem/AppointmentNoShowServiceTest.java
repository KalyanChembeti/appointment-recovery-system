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
import com.recoverysystem.exception.AppointmentNotFoundException;
import com.recoverysystem.exception.AppointmentNotScheduledException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import com.recoverysystem.service.AppointmentNoShowService;
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
class AppointmentNoShowServiceTest {

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
    private AppointmentNoShowService appointmentNoShowService;

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
    void scheduledAppointmentIsMarkedNoShowWithExactlyOneAudit() {
        NoShowFixture fixture = createFixture(
                AppointmentStatus.SCHEDULED, Instant.now().minusSeconds(7200));

        Appointment result = appointmentNoShowService.markNoShow(
                fixture.appointmentId(), fixture.receptionistId());

        assertEquals(AppointmentStatus.NO_SHOW, result.getStatus());
        assertEquals(
                AppointmentStatus.NO_SHOW,
                appointmentRepository.findById(fixture.appointmentId()).orElseThrow().getStatus());
        List<AuditLog> audits = operationAudits(fixture.appointmentId(), List.of(), List.of());
        assertEquals(1, audits.size());
        AuditLog audit = audits.getFirst();
        assertEquals("Appointment", audit.getEntityType());
        assertEquals(fixture.appointmentId(), audit.getEntityId());
        assertEquals("NO_SHOW", audit.getAction());
        assertEquals(ActorType.USER, audit.getActorType());
        assertEquals(fixture.receptionistId(), audit.getActorUserId());
        assertNull(audit.getReason());
    }

    @Test
    void noShowReconcilesTwoActiveEntriesAndOneOfferedSlotWithFourAudits() {
        NoShowFixture fixture = createFixture(
                AppointmentStatus.SCHEDULED, Instant.now().minusSeconds(7200));
        WaitlistEntry firstEntry = saveWaitlistEntry(fixture);
        WaitlistEntry secondEntry = saveWaitlistEntry(fixture);
        SlotOffer offer = saveSlotOffer(fixture, firstEntry.getId(), SlotOfferStatus.OFFERED);

        appointmentNoShowService.markNoShow(
                fixture.appointmentId(), fixture.receptionistId());

        assertEquals(WaitlistEntryStatus.REMOVED, reloadedEntry(firstEntry).getStatus());
        assertEquals(WaitlistEntryStatus.REMOVED, reloadedEntry(secondEntry).getStatus());
        assertEquals(SlotOfferStatus.CANCELLED, reloadedOffer(offer).getStatus());

        List<AuditLog> audits = operationAudits(
                fixture.appointmentId(),
                List.of(firstEntry.getId(), secondEntry.getId()),
                List.of(offer.getId()));
        assertEquals(4, audits.size());
        assertEquals(1, matchingAudits(audits, "Appointment", fixture.appointmentId(), "NO_SHOW"));
        assertEquals(1, matchingAudits(audits, "WaitlistEntry", firstEntry.getId(), "REMOVE"));
        assertEquals(1, matchingAudits(audits, "WaitlistEntry", secondEntry.getId(), "REMOVE"));
        assertEquals(1, matchingAudits(audits, "SlotOffer", offer.getId(), "CANCEL"));
        AuditLog offerAudit = audits.stream()
                .filter(log -> "SlotOffer".equals(log.getEntityType()))
                .findFirst()
                .orElseThrow();
        assertEquals("APPOINTMENT_NOSHOW", offerAudit.getReason());
    }

    @Test
    void noShowCancelsOnlyOfferedSlotAndLeavesDeclinedSlotUntouched() {
        NoShowFixture fixture = createFixture(
                AppointmentStatus.SCHEDULED, Instant.now().minusSeconds(7200));
        WaitlistEntry offeredEntry = saveWaitlistEntry(fixture);
        WaitlistEntry declinedEntry = saveWaitlistEntry(fixture);
        SlotOffer offered = saveSlotOffer(fixture, offeredEntry.getId(), SlotOfferStatus.OFFERED);
        SlotOffer declined = saveSlotOffer(fixture, declinedEntry.getId(), SlotOfferStatus.DECLINED);
        Instant declinedUpdatedAt = declined.getUpdatedAt();

        appointmentNoShowService.markNoShow(
                fixture.appointmentId(), fixture.receptionistId());

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
        assertEquals(4, audits.size());
        assertEquals(0, audits.stream()
                .filter(log -> "SlotOffer".equals(log.getEntityType()))
                .filter(log -> declined.getId().equals(log.getEntityId()))
                .count());
    }

    @Test
    void nonScheduledAppointmentIsRejected() {
        NoShowFixture fixture = createFixture(
                AppointmentStatus.COMPLETED, Instant.now().minusSeconds(7200));

        assertThrows(
                AppointmentNotScheduledException.class,
                () -> appointmentNoShowService.markNoShow(
                        fixture.appointmentId(), fixture.receptionistId()));

        assertEquals(
                AppointmentStatus.COMPLETED,
                appointmentRepository.findById(fixture.appointmentId()).orElseThrow().getStatus());
        assertTrue(operationAudits(fixture.appointmentId(), List.of(), List.of()).isEmpty());
    }

    @Test
    void nonexistentAppointmentIsRejected() {
        long nonexistentAppointmentId = Long.MAX_VALUE;

        assertThrows(
                AppointmentNotFoundException.class,
                () -> appointmentNoShowService.markNoShow(nonexistentAppointmentId, 1L));
    }

    @Test
    void futureScheduledAppointmentCanStillBeMarkedNoShow() {
        NoShowFixture fixture = createFixture(
                AppointmentStatus.SCHEDULED, Instant.now().plusSeconds(7200));

        Appointment result = appointmentNoShowService.markNoShow(
                fixture.appointmentId(), fixture.receptionistId());

        assertEquals(AppointmentStatus.NO_SHOW, result.getStatus());
        assertEquals(
                AppointmentStatus.NO_SHOW,
                appointmentRepository.findById(fixture.appointmentId()).orElseThrow().getStatus());
        assertEquals(1, operationAudits(fixture.appointmentId(), List.of(), List.of()).size());
    }

    @Test
    void auditActorIsUserAcrossFullNoShowCascade() {
        NoShowFixture fixture = createFixture(
                AppointmentStatus.SCHEDULED, Instant.now().minusSeconds(7200));
        WaitlistEntry firstEntry = saveWaitlistEntry(fixture);
        WaitlistEntry secondEntry = saveWaitlistEntry(fixture);
        SlotOffer offer = saveSlotOffer(fixture, firstEntry.getId(), SlotOfferStatus.OFFERED);

        appointmentNoShowService.markNoShow(
                fixture.appointmentId(), fixture.receptionistId());

        List<AuditLog> audits = operationAudits(
                fixture.appointmentId(),
                List.of(firstEntry.getId(), secondEntry.getId()),
                List.of(offer.getId()));
        assertEquals(4, audits.size());
        assertTrue(audits.stream().allMatch(log -> log.getActorType() == ActorType.USER));
        assertTrue(audits.stream()
                .allMatch(log -> fixture.receptionistId().equals(log.getActorUserId())));
    }

    @Test
    void successfulNoShowDoesNotCreateRecoveryJob() {
        NoShowFixture fixture = createFixture(
                AppointmentStatus.SCHEDULED, Instant.now().minusSeconds(7200));
        long recoveryJobCountBefore = recoveryJobRepository.count();

        appointmentNoShowService.markNoShow(
                fixture.appointmentId(), fixture.receptionistId());

        assertEquals(recoveryJobCountBefore, recoveryJobRepository.count());
        assertEquals(
                AppointmentStatus.NO_SHOW,
                appointmentRepository.findById(fixture.appointmentId()).orElseThrow().getStatus());
    }

    private NoShowFixture createFixture(AppointmentStatus appointmentStatus, Instant startAt) {
        Specialty specialty = saveSpecialty();
        AppointmentType appointmentType = saveAppointmentType(specialty.getId());
        Provider provider = saveProvider(specialty.getId());
        User patient = saveUser(UserRole.PATIENT);
        User receptionist = saveUser(UserRole.RECEPTIONIST);
        Appointment appointment = saveAppointment(
                patient.getId(),
                provider.getId(),
                appointmentType.getId(),
                startAt,
                startAt.plusSeconds(3600),
                appointmentStatus);
        return new NoShowFixture(
                patient.getId(),
                receptionist.getId(),
                provider.getId(),
                appointmentType.getId(),
                appointment.getId(),
                startAt);
    }

    private WaitlistEntry saveWaitlistEntry(NoShowFixture fixture) {
        LocalDate appointmentDate = fixture.startAt().atZone(ZoneOffset.UTC).toLocalDate();
        WaitlistEntry waitlistEntry = new WaitlistEntry();
        waitlistEntry.setPatientId(fixture.patientId());
        waitlistEntry.setCurrentAppointmentId(fixture.appointmentId());
        waitlistEntry.setAppointmentTypeId(fixture.appointmentTypeId());
        waitlistEntry.setEarliestAppointmentDate(appointmentDate);
        waitlistEntry.setLatestAppointmentDate(appointmentDate.plusDays(14));
        waitlistEntry.setPreferredTimeOfDay(TimeOfDayPreference.ANY);
        waitlistEntry.setStatus(WaitlistEntryStatus.ACTIVE);
        return waitlistEntryRepository.saveAndFlush(waitlistEntry);
    }

    private SlotOffer saveSlotOffer(
            NoShowFixture fixture, Long waitlistEntryId, SlotOfferStatus status) {
        long sequence = UNIQUE_SEQUENCE.incrementAndGet();
        Instant sourceStart = Instant.parse("2075-01-01T00:00:00Z")
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
        specialty.setName(uniqueValue("No Show Specialty"));
        specialty.setDescription("Appointment no-show test specialty");
        return specialtyRepository.saveAndFlush(specialty);
    }

    private AppointmentType saveAppointmentType(Long specialtyId) {
        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName(uniqueValue("No Show Appointment Type"));
        appointmentType.setDurationMinutes(60);
        appointmentType.setSpecialtyId(specialtyId);
        appointmentType.setDescription("Appointment no-show test type");
        appointmentType.setActive(true);
        return appointmentTypeRepository.saveAndFlush(appointmentType);
    }

    private Provider saveProvider(Long specialtyId) {
        User providerUser = saveUser(UserRole.PROVIDER);
        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(specialtyId);
        provider.setLicenseNumber(uniqueValue("NO-SHOW-LICENSE"));
        provider.setQualifications("Appointment no-show test provider");
        return providerRepository.saveAndFlush(provider);
    }

    private User saveUser(UserRole role) {
        User user = new User();
        user.setEmail(uniqueValue(role.name().toLowerCase()) + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName("No-show test " + role.name().toLowerCase());
        return userRepository.saveAndFlush(user);
    }

    private WaitlistEntry reloadedEntry(WaitlistEntry waitlistEntry) {
        return waitlistEntryRepository.findById(waitlistEntry.getId()).orElseThrow();
    }

    private SlotOffer reloadedOffer(SlotOffer slotOffer) {
        return slotOfferRepository.findById(slotOffer.getId()).orElseThrow();
    }

    private List<AuditLog> operationAudits(
            Long appointmentId, List<Long> waitlistEntryIds, List<Long> slotOfferIds) {
        List<AuditLog> result = new ArrayList<>();
        for (AuditLog audit : auditLogRepository.findAll()) {
            if (("Appointment".equals(audit.getEntityType())
                            && appointmentId.equals(audit.getEntityId()))
                    || ("WaitlistEntry".equals(audit.getEntityType())
                            && waitlistEntryIds.contains(audit.getEntityId()))
                    || ("SlotOffer".equals(audit.getEntityType())
                            && slotOfferIds.contains(audit.getEntityId()))) {
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

    private record NoShowFixture(
            Long patientId,
            Long receptionistId,
            Long providerId,
            Long appointmentTypeId,
            Long appointmentId,
            Instant startAt) {
    }
}

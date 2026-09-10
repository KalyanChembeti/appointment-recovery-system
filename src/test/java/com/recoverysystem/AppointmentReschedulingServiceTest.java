package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.ProviderSchedule;
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
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import com.recoverysystem.service.AppointmentReschedulingService;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
class AppointmentReschedulingServiceTest {

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
    private AppointmentReschedulingService appointmentReschedulingService;

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
    private WaitlistEntryRepository waitlistEntryRepository;

    @Autowired
    private RecoveryJobRepository recoveryJobRepository;

    @Autowired
    private SlotOfferRepository slotOfferRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void sameProviderRescheduleCreatesReplacementRecoveryJobAndThreeAudits() {
        RescheduleFixture fixture = createFixture(AppointmentStatus.SCHEDULED, 60);
        Instant newStart = atClinicTime(fixture.oldDate().plusDays(7), 12, 0);

        Appointment replacement = appointmentReschedulingService.rescheduleAppointment(
                fixture.oldAppointmentId(),
                fixture.oldProviderId(),
                fixture.oldAppointmentTypeId(),
                newStart,
                fixture.patientId());

        assertEquals(AppointmentStatus.SCHEDULED, replacement.getStatus());
        assertEquals(fixture.patientId(), replacement.getPatientId());
        assertEquals(fixture.oldProviderId(), replacement.getProviderId());
        assertEquals(newStart, replacement.getStartAt());
        assertEquals(newStart.plusSeconds(3600), replacement.getEndAt());

        Appointment oldAppointment = reloadedAppointment(fixture.oldAppointmentId());
        assertEquals(AppointmentStatus.CANCELLED, oldAppointment.getStatus());
        assertEquals(CancellationReason.RESCHEDULED, oldAppointment.getCancellationReason());
        assertEquals(replacement.getId(), oldAppointment.getReplacedByAppointmentId());
        RecoveryJob recoveryJob = recoveryJobFor(fixture.oldAppointmentId());
        assertEquals(RecoveryJobStatus.OPEN, recoveryJob.getStatus());

        List<AuditLog> audits = operationAudits(
                fixture.oldAppointmentId(), replacement.getId(), List.of(), List.of());
        assertEquals(3, audits.size());
        assertEquals(1, matchingAudits(audits, "Appointment", replacement.getId(), "CREATE"));
        assertEquals(1, matchingAudits(
                audits, "Appointment", fixture.oldAppointmentId(), "CANCEL"));
        assertEquals(1, matchingAudits(audits, "RecoveryJob", recoveryJob.getId(), "CREATE"));
    }

    @Test
    void differentProviderRescheduleReconcilesTwoEntriesAndOfferedSlot() {
        RescheduleFixture fixture = createFixture(AppointmentStatus.SCHEDULED, 60);
        Provider newProvider = saveProviderWithSchedules(fixture.specialtyId());
        WaitlistEntry firstEntry = saveWaitlistEntry(fixture);
        WaitlistEntry secondEntry = saveWaitlistEntry(fixture);
        SlotOffer offer = saveSlotOffer(fixture, firstEntry.getId(), SlotOfferStatus.OFFERED);
        Instant newStart = atClinicTime(fixture.oldDate().plusDays(7), 11, 0);

        Appointment replacement = appointmentReschedulingService.rescheduleAppointment(
                fixture.oldAppointmentId(),
                newProvider.getId(),
                fixture.oldAppointmentTypeId(),
                newStart,
                fixture.patientId());

        assertEquals(newProvider.getId(), replacement.getProviderId());
        assertEquals(WaitlistEntryStatus.REMOVED, reloadedEntry(firstEntry).getStatus());
        assertEquals(WaitlistEntryStatus.REMOVED, reloadedEntry(secondEntry).getStatus());
        assertEquals(SlotOfferStatus.CANCELLED, reloadedOffer(offer).getStatus());

        List<AuditLog> audits = operationAudits(
                fixture.oldAppointmentId(),
                replacement.getId(),
                List.of(firstEntry.getId(), secondEntry.getId()),
                List.of(offer.getId()));
        assertEquals(6, audits.size());
        AuditLog offerAudit = findAudit(audits, "SlotOffer", offer.getId(), "CANCEL");
        assertEquals("APPOINTMENT_RESCHEDULED", offerAudit.getReason());
    }

    @Test
    void declinedSiblingOfferIsUntouchedAndUnaudited() {
        RescheduleFixture fixture = createFixture(AppointmentStatus.SCHEDULED, 60);
        WaitlistEntry entry = saveWaitlistEntry(fixture);
        SlotOffer offered = saveSlotOffer(fixture, entry.getId(), SlotOfferStatus.OFFERED);
        SlotOffer declined = saveSlotOffer(fixture, entry.getId(), SlotOfferStatus.DECLINED);
        Instant declinedUpdatedAt = declined.getUpdatedAt();
        Instant newStart = atClinicTime(fixture.oldDate().plusDays(7), 12, 0);

        Appointment replacement = appointmentReschedulingService.rescheduleAppointment(
                fixture.oldAppointmentId(),
                fixture.oldProviderId(),
                fixture.oldAppointmentTypeId(),
                newStart,
                null);

        assertEquals(WaitlistEntryStatus.REMOVED, reloadedEntry(entry).getStatus());
        assertEquals(SlotOfferStatus.CANCELLED, reloadedOffer(offered).getStatus());
        SlotOffer reloadedDeclined = reloadedOffer(declined);
        assertEquals(SlotOfferStatus.DECLINED, reloadedDeclined.getStatus());
        assertEquals(declinedUpdatedAt, reloadedDeclined.getUpdatedAt());
        List<AuditLog> audits = operationAudits(
                fixture.oldAppointmentId(),
                replacement.getId(),
                List.of(entry.getId()),
                List.of(offered.getId(), declined.getId()));
        assertEquals(0, audits.stream()
                .filter(log -> "SlotOffer".equals(log.getEntityType()))
                .filter(log -> declined.getId().equals(log.getEntityId()))
                .count());
    }

    @Test
    void activeBlockOnNewProviderRejectsAndRollsBack() {
        RescheduleFixture fixture = createFixture(AppointmentStatus.SCHEDULED, 60);
        Provider newProvider = saveProviderWithSchedules(fixture.specialtyId());
        Instant newStart = atClinicTime(fixture.oldDate().plusDays(7), 12, 0);
        saveBlock(newProvider.getId(), newStart, newStart.plusSeconds(3600),
                ProviderUnavailabilityStatus.ACTIVE);
        long appointmentCount = appointmentRepository.count();
        long auditCount = auditLogRepository.count();

        assertThrows(
                ProviderUnavailableException.class,
                () -> appointmentReschedulingService.rescheduleAppointment(
                        fixture.oldAppointmentId(),
                        newProvider.getId(),
                        fixture.oldAppointmentTypeId(),
                        newStart,
                        null));

        assertOldAppointmentUnchanged(fixture);
        assertEquals(appointmentCount, appointmentRepository.count());
        assertEquals(auditCount, auditLogRepository.count());
        assertTrue(recoveryJobsFor(fixture.oldAppointmentId()).isEmpty());
    }

    @Test
    void pendingBlockOnNewProviderAlsoRejectsAndRollsBack() {
        RescheduleFixture fixture = createFixture(AppointmentStatus.SCHEDULED, 60);
        Provider newProvider = saveProviderWithSchedules(fixture.specialtyId());
        Instant newStart = atClinicTime(fixture.oldDate().plusDays(7), 12, 0);
        saveBlock(newProvider.getId(), newStart, newStart.plusSeconds(3600),
                ProviderUnavailabilityStatus.PENDING);
        long appointmentCount = appointmentRepository.count();

        assertThrows(
                ProviderUnavailableException.class,
                () -> appointmentReschedulingService.rescheduleAppointment(
                        fixture.oldAppointmentId(),
                        newProvider.getId(),
                        fixture.oldAppointmentTypeId(),
                        newStart,
                        null));

        assertOldAppointmentUnchanged(fixture);
        assertEquals(appointmentCount, appointmentRepository.count());
        assertTrue(recoveryJobsFor(fixture.oldAppointmentId()).isEmpty());
    }

    @Test
    void activeBlockOnOldIntervalAllowsRescheduleButSuppressesRecoveryJob() {
        RescheduleFixture fixture = createFixture(AppointmentStatus.SCHEDULED, 60);
        saveBlock(
                fixture.oldProviderId(),
                fixture.oldStart(),
                fixture.oldEnd(),
                ProviderUnavailabilityStatus.ACTIVE);
        Instant newStart = atClinicTime(fixture.oldDate().plusDays(7), 12, 0);

        Appointment replacement = appointmentReschedulingService.rescheduleAppointment(
                fixture.oldAppointmentId(),
                fixture.oldProviderId(),
                fixture.oldAppointmentTypeId(),
                newStart,
                null);

        assertNotNull(replacement.getId());
        assertEquals(AppointmentStatus.CANCELLED,
                reloadedAppointment(fixture.oldAppointmentId()).getStatus());
        assertTrue(recoveryJobsFor(fixture.oldAppointmentId()).isEmpty());
        List<AuditLog> audits = operationAudits(
                fixture.oldAppointmentId(), replacement.getId(), List.of(), List.of());
        assertEquals(2, audits.size());
        assertEquals(0, audits.stream()
                .filter(log -> "RecoveryJob".equals(log.getEntityType()))
                .count());
    }

    @Test
    void newAppointmentTypeSpecialtyMismatchIsRejected() {
        RescheduleFixture fixture = createFixture(AppointmentStatus.SCHEDULED, 60);
        Specialty otherSpecialty = saveSpecialty();
        AppointmentType mismatchedType = saveAppointmentType(otherSpecialty.getId(), 60);
        Instant newStart = atClinicTime(fixture.oldDate().plusDays(7), 12, 0);
        long appointmentCount = appointmentRepository.count();

        assertThrows(
                AppointmentTypeSpecialtyMismatchException.class,
                () -> appointmentReschedulingService.rescheduleAppointment(
                        fixture.oldAppointmentId(),
                        fixture.oldProviderId(),
                        mismatchedType.getId(),
                        newStart,
                        null));

        assertOldAppointmentUnchanged(fixture);
        assertEquals(appointmentCount, appointmentRepository.count());
    }

    @Test
    void derivedNewIntervalOutsideWorkingHoursIsRejected() {
        RescheduleFixture fixture = createFixture(AppointmentStatus.SCHEDULED, 60);
        Instant newStart = atClinicTime(fixture.oldDate().plusDays(7), 16, 30);
        long appointmentCount = appointmentRepository.count();

        assertThrows(
                ProviderUnavailableException.class,
                () -> appointmentReschedulingService.rescheduleAppointment(
                        fixture.oldAppointmentId(),
                        fixture.oldProviderId(),
                        fixture.oldAppointmentTypeId(),
                        newStart,
                        null));

        assertOldAppointmentUnchanged(fixture);
        assertEquals(appointmentCount, appointmentRepository.count());
    }

    @Test
    void oldAppointmentNotScheduledIsRejected() {
        RescheduleFixture fixture = createFixture(AppointmentStatus.CANCELLED, 60);
        Instant newStart = atClinicTime(fixture.oldDate().plusDays(7), 12, 0);
        long appointmentCount = appointmentRepository.count();
        long auditCount = auditLogRepository.count();

        assertThrows(
                AppointmentNotScheduledException.class,
                () -> appointmentReschedulingService.rescheduleAppointment(
                        fixture.oldAppointmentId(),
                        fixture.oldProviderId(),
                        fixture.oldAppointmentTypeId(),
                        newStart,
                        null));

        Appointment oldAppointment = reloadedAppointment(fixture.oldAppointmentId());
        assertEquals(AppointmentStatus.CANCELLED, oldAppointment.getStatus());
        assertEquals(CancellationReason.PATIENT_CANCELLED,
                oldAppointment.getCancellationReason());
        assertEquals(appointmentCount, appointmentRepository.count());
        assertEquals(auditCount, auditLogRepository.count());
    }

    @Test
    void nonexistentOldAppointmentIsRejected() {
        long appointmentCount = appointmentRepository.count();
        long auditCount = auditLogRepository.count();

        assertThrows(
                AppointmentNotFoundException.class,
                () -> appointmentReschedulingService.rescheduleAppointment(
                        Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Instant.now(), null));

        assertEquals(appointmentCount, appointmentRepository.count());
        assertEquals(auditCount, auditLogRepository.count());
    }

    @Test
    void nonexistentNewProviderIsRejected() {
        RescheduleFixture fixture = createFixture(AppointmentStatus.SCHEDULED, 60);
        Instant newStart = atClinicTime(fixture.oldDate().plusDays(7), 12, 0);
        long appointmentCount = appointmentRepository.count();

        assertThrows(
                ProviderNotFoundException.class,
                () -> appointmentReschedulingService.rescheduleAppointment(
                        fixture.oldAppointmentId(),
                        Long.MAX_VALUE,
                        fixture.oldAppointmentTypeId(),
                        newStart,
                        null));

        assertOldAppointmentUnchanged(fixture);
        assertEquals(appointmentCount, appointmentRepository.count());
    }

    @Test
    void overlapWithDifferentAppointmentForSamePatientRollsBack() {
        RescheduleFixture fixture = createFixture(AppointmentStatus.SCHEDULED, 60);
        Provider destinationProvider = saveProviderWithSchedules(fixture.specialtyId());
        Provider conflictingProvider = saveProviderWithSchedules(fixture.specialtyId());
        Instant newStart = atClinicTime(fixture.oldDate().plusDays(7), 12, 0);
        saveAppointment(
                fixture.patientId(),
                conflictingProvider.getId(),
                fixture.oldAppointmentTypeId(),
                newStart,
                newStart.plusSeconds(3600),
                AppointmentStatus.SCHEDULED);
        long appointmentCount = appointmentRepository.count();
        long auditCount = auditLogRepository.count();

        assertThrows(
                PatientDoubleBookedException.class,
                () -> appointmentReschedulingService.rescheduleAppointment(
                        fixture.oldAppointmentId(),
                        destinationProvider.getId(),
                        fixture.oldAppointmentTypeId(),
                        newStart,
                        null));

        assertOldAppointmentUnchanged(fixture);
        assertEquals(appointmentCount, appointmentRepository.count());
        assertEquals(auditCount, auditLogRepository.count());
    }

    @Test
    void overlapWithOldAppointmentsOwnIntervalIsKnownCleanFailure() {
        RescheduleFixture fixture = createFixture(AppointmentStatus.SCHEDULED, 60);
        long appointmentCount = appointmentRepository.count();
        long auditCount = auditLogRepository.count();

        assertThrows(
                PatientDoubleBookedException.class,
                () -> appointmentReschedulingService.rescheduleAppointment(
                        fixture.oldAppointmentId(),
                        fixture.oldProviderId(),
                        fixture.oldAppointmentTypeId(),
                        fixture.oldStart(),
                        null));

        assertOldAppointmentUnchanged(fixture);
        assertEquals(appointmentCount, appointmentRepository.count());
        assertEquals(auditCount, auditLogRepository.count());
        assertTrue(recoveryJobsFor(fixture.oldAppointmentId()).isEmpty());
    }

    @Test
    void overlapWithDifferentPatientsAppointmentForNewProviderRollsBack() {
        RescheduleFixture fixture = createFixture(AppointmentStatus.SCHEDULED, 60);
        Provider newProvider = saveProviderWithSchedules(fixture.specialtyId());
        User otherPatient = saveUser(UserRole.PATIENT);
        Instant newStart = atClinicTime(fixture.oldDate().plusDays(7), 12, 0);
        saveAppointment(
                otherPatient.getId(),
                newProvider.getId(),
                fixture.oldAppointmentTypeId(),
                newStart,
                newStart.plusSeconds(3600),
                AppointmentStatus.SCHEDULED);
        long appointmentCount = appointmentRepository.count();
        long auditCount = auditLogRepository.count();

        assertThrows(
                ProviderDoubleBookedException.class,
                () -> appointmentReschedulingService.rescheduleAppointment(
                        fixture.oldAppointmentId(),
                        newProvider.getId(),
                        fixture.oldAppointmentTypeId(),
                        newStart,
                        null));

        assertOldAppointmentUnchanged(fixture);
        assertEquals(appointmentCount, appointmentRepository.count());
        assertEquals(auditCount, auditLogRepository.count());
    }

    @Test
    void actorAttributionIsConsistentAcrossEachFullCascade() {
        RescheduleFixture systemFixture = createFixture(AppointmentStatus.SCHEDULED, 60);
        WaitlistEntry systemEntry = saveWaitlistEntry(systemFixture);
        SlotOffer systemOffer =
                saveSlotOffer(systemFixture, systemEntry.getId(), SlotOfferStatus.OFFERED);
        Appointment systemReplacement = appointmentReschedulingService.rescheduleAppointment(
                systemFixture.oldAppointmentId(),
                systemFixture.oldProviderId(),
                systemFixture.oldAppointmentTypeId(),
                atClinicTime(systemFixture.oldDate().plusDays(7), 12, 0),
                null);

        User actor = saveUser(UserRole.RECEPTIONIST);
        RescheduleFixture userFixture = createFixture(AppointmentStatus.SCHEDULED, 60);
        WaitlistEntry userEntry = saveWaitlistEntry(userFixture);
        SlotOffer userOffer =
                saveSlotOffer(userFixture, userEntry.getId(), SlotOfferStatus.OFFERED);
        Appointment userReplacement = appointmentReschedulingService.rescheduleAppointment(
                userFixture.oldAppointmentId(),
                userFixture.oldProviderId(),
                userFixture.oldAppointmentTypeId(),
                atClinicTime(userFixture.oldDate().plusDays(7), 12, 0),
                actor.getId());

        List<AuditLog> systemAudits = operationAudits(
                systemFixture.oldAppointmentId(),
                systemReplacement.getId(),
                List.of(systemEntry.getId()),
                List.of(systemOffer.getId()));
        assertEquals(5, systemAudits.size());
        assertTrue(systemAudits.stream()
                .allMatch(log -> log.getActorType() == ActorType.SYSTEM));
        assertTrue(systemAudits.stream().allMatch(log -> log.getActorUserId() == null));

        List<AuditLog> userAudits = operationAudits(
                userFixture.oldAppointmentId(),
                userReplacement.getId(),
                List.of(userEntry.getId()),
                List.of(userOffer.getId()));
        assertEquals(5, userAudits.size());
        assertTrue(userAudits.stream().allMatch(log -> log.getActorType() == ActorType.USER));
        assertTrue(userAudits.stream()
                .allMatch(log -> actor.getId().equals(log.getActorUserId())));
    }

    @Test
    void replacementEndUsesNewAppointmentTypesDuration() {
        RescheduleFixture fixture = createFixture(AppointmentStatus.SCHEDULED, 45);
        AppointmentType longerType = saveAppointmentType(fixture.specialtyId(), 90);
        Instant newStart = atClinicTime(fixture.oldDate().plusDays(7), 12, 0);

        Appointment replacement = appointmentReschedulingService.rescheduleAppointment(
                fixture.oldAppointmentId(),
                fixture.oldProviderId(),
                longerType.getId(),
                newStart,
                null);

        assertEquals(longerType.getId(), replacement.getAppointmentTypeId());
        assertEquals(newStart.plusSeconds(5400), replacement.getEndAt());
        assertNotEquals(newStart.plusSeconds(2700), replacement.getEndAt());
    }

    private RescheduleFixture createFixture(
            AppointmentStatus appointmentStatus, int originalDurationMinutes) {
        Specialty specialty = saveSpecialty();
        AppointmentType appointmentType =
                saveAppointmentType(specialty.getId(), originalDurationMinutes);
        Provider provider = saveProviderWithSchedules(specialty.getId());
        User patient = saveUser(UserRole.PATIENT);
        LocalDate oldDate = LocalDate.of(2050, 1, 10)
                .plusDays(UNIQUE_SEQUENCE.incrementAndGet() * 14);
        Instant oldStart = atClinicTime(oldDate, 12, 0);
        Instant oldEnd = oldStart.plusSeconds(originalDurationMinutes * 60L);
        Appointment oldAppointment = saveAppointment(
                patient.getId(),
                provider.getId(),
                appointmentType.getId(),
                oldStart,
                oldEnd,
                appointmentStatus);
        return new RescheduleFixture(
                patient.getId(),
                specialty.getId(),
                provider.getId(),
                appointmentType.getId(),
                oldAppointment.getId(),
                oldDate,
                oldStart,
                oldEnd);
    }

    private WaitlistEntry saveWaitlistEntry(RescheduleFixture fixture) {
        WaitlistEntry waitlistEntry = new WaitlistEntry();
        waitlistEntry.setPatientId(fixture.patientId());
        waitlistEntry.setCurrentAppointmentId(fixture.oldAppointmentId());
        waitlistEntry.setAppointmentTypeId(fixture.oldAppointmentTypeId());
        waitlistEntry.setEarliestAppointmentDate(fixture.oldDate());
        waitlistEntry.setLatestAppointmentDate(fixture.oldDate().plusDays(30));
        waitlistEntry.setPreferredTimeOfDay(TimeOfDayPreference.ANY);
        waitlistEntry.setStatus(WaitlistEntryStatus.ACTIVE);
        return waitlistEntryRepository.saveAndFlush(waitlistEntry);
    }

    private SlotOffer saveSlotOffer(
            RescheduleFixture fixture, Long waitlistEntryId, SlotOfferStatus status) {
        long sequence = UNIQUE_SEQUENCE.incrementAndGet();
        Instant sourceStart = Instant.parse("2080-01-01T14:00:00Z")
                .plusSeconds(sequence * 7200);
        Appointment sourceAppointment = saveAppointment(
                fixture.patientId(),
                fixture.oldProviderId(),
                fixture.oldAppointmentTypeId(),
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
            Long providerId,
            Instant startAt,
            Instant endAt,
            ProviderUnavailabilityStatus status) {
        ProviderUnavailability block = new ProviderUnavailability();
        block.setProviderId(providerId);
        block.setStartAt(startAt);
        block.setEndAt(endAt);
        block.setStatus(status);
        block.setReason("Rescheduling test block");
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
        specialty.setName(uniqueValue("Reschedule Specialty"));
        specialty.setDescription("Appointment rescheduling test specialty");
        return specialtyRepository.saveAndFlush(specialty);
    }

    private AppointmentType saveAppointmentType(Long specialtyId, int durationMinutes) {
        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName(uniqueValue("Reschedule Appointment Type"));
        appointmentType.setDurationMinutes(durationMinutes);
        appointmentType.setSpecialtyId(specialtyId);
        appointmentType.setDescription("Appointment rescheduling test type");
        appointmentType.setActive(true);
        return appointmentTypeRepository.saveAndFlush(appointmentType);
    }

    private Provider saveProviderWithSchedules(Long specialtyId) {
        User providerUser = saveUser(UserRole.PROVIDER);
        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(specialtyId);
        provider.setLicenseNumber(uniqueValue("RESCHEDULE-LICENSE"));
        provider.setQualifications("Appointment rescheduling test provider");
        Provider savedProvider = providerRepository.saveAndFlush(provider);

        for (DayOfWeek dayOfWeek : DayOfWeek.values()) {
            ProviderSchedule schedule = new ProviderSchedule();
            schedule.setProviderId(savedProvider.getId());
            schedule.setDayOfWeek(dayOfWeek);
            schedule.setStartTime(LocalTime.of(9, 0));
            schedule.setEndTime(LocalTime.of(17, 0));
            schedule.setActive(true);
            providerScheduleRepository.save(schedule);
        }
        providerScheduleRepository.flush();
        return savedProvider;
    }

    private User saveUser(UserRole role) {
        User user = new User();
        user.setEmail(uniqueValue(role.name().toLowerCase()) + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName("Rescheduling test " + role.name().toLowerCase());
        return userRepository.saveAndFlush(user);
    }

    private void assertOldAppointmentUnchanged(RescheduleFixture fixture) {
        Appointment oldAppointment = reloadedAppointment(fixture.oldAppointmentId());
        assertEquals(AppointmentStatus.SCHEDULED, oldAppointment.getStatus());
        assertNull(oldAppointment.getCancellationReason());
        assertNull(oldAppointment.getReplacedByAppointmentId());
    }

    private Appointment reloadedAppointment(Long appointmentId) {
        return appointmentRepository.findById(appointmentId).orElseThrow();
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
            Long oldAppointmentId,
            Long newAppointmentId,
            List<Long> waitlistEntryIds,
            List<Long> slotOfferIds) {
        List<Long> recoveryJobIds = recoveryJobsFor(oldAppointmentId).stream()
                .map(RecoveryJob::getId)
                .toList();
        List<AuditLog> result = new ArrayList<>();
        for (AuditLog audit : auditLogRepository.findAll()) {
            if (("Appointment".equals(audit.getEntityType())
                            && (oldAppointmentId.equals(audit.getEntityId())
                                    || newAppointmentId.equals(audit.getEntityId())))
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

    private AuditLog findAudit(
            List<AuditLog> audits, String entityType, Long entityId, String action) {
        return audits.stream()
                .filter(log -> entityType.equals(log.getEntityType()))
                .filter(log -> entityId.equals(log.getEntityId()))
                .filter(log -> action.equals(log.getAction()))
                .findFirst()
                .orElseThrow();
    }

    private long matchingAudits(
            List<AuditLog> audits, String entityType, Long entityId, String action) {
        return audits.stream()
                .filter(log -> entityType.equals(log.getEntityType()))
                .filter(log -> entityId.equals(log.getEntityId()))
                .filter(log -> action.equals(log.getAction()))
                .count();
    }

    private static Instant atClinicTime(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(CLINIC_TIME_ZONE).toInstant();
    }

    private static String uniqueValue(String prefix) {
        return prefix + "-" + UNIQUE_SEQUENCE.incrementAndGet();
    }

    private record RescheduleFixture(
            Long patientId,
            Long specialtyId,
            Long oldProviderId,
            Long oldAppointmentTypeId,
            Long oldAppointmentId,
            LocalDate oldDate,
            Instant oldStart,
            Instant oldEnd) {
    }
}

package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.ProviderSchedule;
import com.recoverysystem.domain.entity.ProviderUnavailability;
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.entity.SchedulingPolicy;
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
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderScheduleRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SchedulingPolicyRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@Transactional
class ReferenceEntityMappingTest {

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
    private ApplicationContext applicationContext;

    @Autowired
    private EntityManager entityManager;

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
    private SchedulingPolicyRepository schedulingPolicyRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void contextLoadsWithValidatedEntityMappings() {
        assertNotNull(applicationContext);
    }

    @Test
    void userRepositoryRoundTrip() {
        User user = new User();
        user.setEmail(uniqueValue("patient") + "@example.com");
        user.setPasswordHash("hashed-password");
        user.setRole(UserRole.PATIENT);
        user.setDisplayName("Test Patient");

        User saved = userRepository.saveAndFlush(user);
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        entityManager.clear();
        User retrieved = userRepository.findById(saved.getId()).orElseThrow();

        assertEquals(saved.getId(), retrieved.getId());
        assertEquals(saved.getEmail(), retrieved.getEmail());
        assertEquals(saved.getPasswordHash(), retrieved.getPasswordHash());
        assertEquals(saved.getRole(), retrieved.getRole());
        assertEquals(saved.getDisplayName(), retrieved.getDisplayName());
        assertEquals(saved.getCreatedAt(), retrieved.getCreatedAt());
        assertEquals(saved.getUpdatedAt(), retrieved.getUpdatedAt());
    }

    @Test
    void specialtyRepositoryRoundTrip() {
        Specialty specialty = new Specialty();
        specialty.setName(uniqueValue("Cardiology"));
        specialty.setDescription("Heart and cardiovascular care");

        Specialty saved = specialtyRepository.saveAndFlush(specialty);
        assertNotNull(saved.getId());

        entityManager.clear();
        Specialty retrieved = specialtyRepository.findById(saved.getId()).orElseThrow();

        assertEquals(saved.getId(), retrieved.getId());
        assertEquals(saved.getName(), retrieved.getName());
        assertEquals(saved.getDescription(), retrieved.getDescription());
    }

    @Test
    void appointmentTypeRepositoryRoundTrip() {
        Specialty specialty = saveSpecialty();

        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName(uniqueValue("Initial consultation"));
        appointmentType.setDurationMinutes(45);
        appointmentType.setSpecialtyId(specialty.getId());
        appointmentType.setDescription("First consultation appointment");
        appointmentType.setActive(true);

        AppointmentType saved = appointmentTypeRepository.saveAndFlush(appointmentType);
        assertNotNull(saved.getId());

        entityManager.clear();
        AppointmentType retrieved = appointmentTypeRepository.findById(saved.getId()).orElseThrow();

        assertEquals(saved.getId(), retrieved.getId());
        assertEquals(saved.getName(), retrieved.getName());
        assertEquals(saved.getDurationMinutes(), retrieved.getDurationMinutes());
        assertEquals(saved.getSpecialtyId(), retrieved.getSpecialtyId());
        assertEquals(saved.getDescription(), retrieved.getDescription());
        assertEquals(saved.isActive(), retrieved.isActive());
    }

    @Test
    void providerRepositoryRoundTrip() {
        Specialty specialty = saveSpecialty();
        User providerUser = saveProviderUser();

        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(specialty.getId());
        provider.setLicenseNumber(uniqueValue("LICENSE"));
        provider.setQualifications("Board certified");

        Provider saved = providerRepository.saveAndFlush(provider);
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        entityManager.clear();
        Provider retrieved = providerRepository.findById(saved.getId()).orElseThrow();

        assertEquals(saved.getId(), retrieved.getId());
        assertEquals(saved.getUserId(), retrieved.getUserId());
        assertEquals(saved.getSpecialtyId(), retrieved.getSpecialtyId());
        assertEquals(saved.getLicenseNumber(), retrieved.getLicenseNumber());
        assertEquals(saved.getQualifications(), retrieved.getQualifications());
        assertEquals(saved.getCreatedAt(), retrieved.getCreatedAt());
        assertEquals(saved.getUpdatedAt(), retrieved.getUpdatedAt());
    }

    @Test
    void providerScheduleRepositoryRoundTrip() {
        Provider provider = saveProvider();

        ProviderSchedule schedule = new ProviderSchedule();
        schedule.setProviderId(provider.getId());
        schedule.setDayOfWeek(DayOfWeek.TUESDAY);
        schedule.setStartTime(LocalTime.of(9, 30));
        schedule.setEndTime(LocalTime.of(17, 0));
        schedule.setActive(true);

        ProviderSchedule saved = providerScheduleRepository.saveAndFlush(schedule);
        assertNotNull(saved.getId());

        entityManager.clear();
        ProviderSchedule retrieved = providerScheduleRepository.findById(saved.getId()).orElseThrow();

        assertEquals(saved.getId(), retrieved.getId());
        assertEquals(saved.getProviderId(), retrieved.getProviderId());
        assertEquals(saved.getDayOfWeek(), retrieved.getDayOfWeek());
        assertEquals(saved.getStartTime(), retrieved.getStartTime());
        assertEquals(saved.getEndTime(), retrieved.getEndTime());
        assertEquals(saved.isActive(), retrieved.isActive());
    }

    @Test
    void appointmentRepositoryRoundTrip() {
        Appointment saved = saveAppointment(
                "2031-01-10T14:00:00Z", "2031-01-10T15:00:00Z");

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        entityManager.clear();
        Appointment retrieved = appointmentRepository.findById(saved.getId()).orElseThrow();

        assertEquals(saved.getId(), retrieved.getId());
        assertEquals(saved.getPatientId(), retrieved.getPatientId());
        assertEquals(saved.getProviderId(), retrieved.getProviderId());
        assertEquals(saved.getAppointmentTypeId(), retrieved.getAppointmentTypeId());
        assertEquals(saved.getStartAt(), retrieved.getStartAt());
        assertEquals(saved.getEndAt(), retrieved.getEndAt());
        assertEquals(saved.getStatus(), retrieved.getStatus());
        assertEquals(saved.getCancellationReason(), retrieved.getCancellationReason());
        assertEquals(saved.getReplacedByAppointmentId(), retrieved.getReplacedByAppointmentId());
        assertEquals(saved.getCreatedAt(), retrieved.getCreatedAt());
        assertEquals(saved.getUpdatedAt(), retrieved.getUpdatedAt());
    }

    @Test
    void providerUnavailabilityRepositoryRoundTrip() {
        Provider provider = saveProvider();
        ProviderUnavailability unavailability = new ProviderUnavailability();
        unavailability.setProviderId(provider.getId());
        unavailability.setStartAt(Instant.parse("2031-02-10T14:00:00Z"));
        unavailability.setEndAt(Instant.parse("2031-02-10T17:00:00Z"));
        unavailability.setStatus(ProviderUnavailabilityStatus.ACTIVE);
        unavailability.setReason("Conference attendance");
        unavailability.setActivatedAt(Instant.parse("2031-01-15T12:00:00Z"));
        unavailability.setCancelledAt(null);

        ProviderUnavailability saved = providerUnavailabilityRepository.saveAndFlush(unavailability);
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        entityManager.clear();
        ProviderUnavailability retrieved =
                providerUnavailabilityRepository.findById(saved.getId()).orElseThrow();

        assertEquals(saved.getId(), retrieved.getId());
        assertEquals(saved.getProviderId(), retrieved.getProviderId());
        assertEquals(saved.getStartAt(), retrieved.getStartAt());
        assertEquals(saved.getEndAt(), retrieved.getEndAt());
        assertEquals(saved.getStatus(), retrieved.getStatus());
        assertEquals(saved.getReason(), retrieved.getReason());
        assertEquals(saved.getCreatedAt(), retrieved.getCreatedAt());
        assertEquals(saved.getActivatedAt(), retrieved.getActivatedAt());
        assertEquals(saved.getCancelledAt(), retrieved.getCancelledAt());
        assertEquals(saved.getUpdatedAt(), retrieved.getUpdatedAt());
    }

    @Test
    void recoveryJobRepositoryRoundTrip() {
        Appointment sourceAppointment = saveAppointment(
                "2031-03-10T14:00:00Z", "2031-03-10T15:00:00Z");
        RecoveryJob recoveryJob = new RecoveryJob();
        recoveryJob.setSourceAppointmentId(sourceAppointment.getId());
        recoveryJob.setStatus(RecoveryJobStatus.SUPPRESSED);
        recoveryJob.setFilledAt(null);
        recoveryJob.setSuppressionReason("Lead time closed");

        RecoveryJob saved = recoveryJobRepository.saveAndFlush(recoveryJob);
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        entityManager.clear();
        RecoveryJob retrieved = recoveryJobRepository.findById(saved.getId()).orElseThrow();

        assertEquals(saved.getId(), retrieved.getId());
        assertEquals(saved.getSourceAppointmentId(), retrieved.getSourceAppointmentId());
        assertEquals(saved.getStatus(), retrieved.getStatus());
        assertEquals(saved.getFilledAt(), retrieved.getFilledAt());
        assertEquals(saved.getSuppressionReason(), retrieved.getSuppressionReason());
        assertEquals(saved.getCreatedAt(), retrieved.getCreatedAt());
        assertEquals(saved.getUpdatedAt(), retrieved.getUpdatedAt());
    }

    @Test
    void waitlistEntryRepositoryRoundTrip() {
        Appointment currentAppointment = saveAppointment(
                "2031-04-20T14:00:00Z", "2031-04-20T15:00:00Z");
        WaitlistEntry waitlistEntry = new WaitlistEntry();
        waitlistEntry.setPatientId(currentAppointment.getPatientId());
        waitlistEntry.setCurrentAppointmentId(currentAppointment.getId());
        waitlistEntry.setAppointmentTypeId(currentAppointment.getAppointmentTypeId());
        waitlistEntry.setPreferredProviderId(currentAppointment.getProviderId());
        waitlistEntry.setEarliestAppointmentDate(LocalDate.of(2031, 4, 1));
        waitlistEntry.setLatestAppointmentDate(LocalDate.of(2031, 4, 19));
        waitlistEntry.setPreferredTimeOfDay(TimeOfDayPreference.AFTERNOON);
        waitlistEntry.setStatus(WaitlistEntryStatus.ACTIVE);

        WaitlistEntry saved = waitlistEntryRepository.saveAndFlush(waitlistEntry);
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        entityManager.clear();
        WaitlistEntry retrieved = waitlistEntryRepository.findById(saved.getId()).orElseThrow();

        assertEquals(saved.getId(), retrieved.getId());
        assertEquals(saved.getPatientId(), retrieved.getPatientId());
        assertEquals(saved.getCurrentAppointmentId(), retrieved.getCurrentAppointmentId());
        assertEquals(saved.getAppointmentTypeId(), retrieved.getAppointmentTypeId());
        assertEquals(saved.getPreferredProviderId(), retrieved.getPreferredProviderId());
        assertEquals(saved.getEarliestAppointmentDate(), retrieved.getEarliestAppointmentDate());
        assertEquals(saved.getLatestAppointmentDate(), retrieved.getLatestAppointmentDate());
        assertEquals(saved.getPreferredTimeOfDay(), retrieved.getPreferredTimeOfDay());
        assertEquals(saved.getStatus(), retrieved.getStatus());
        assertEquals(saved.getCreatedAt(), retrieved.getCreatedAt());
        assertEquals(saved.getUpdatedAt(), retrieved.getUpdatedAt());
    }

    @Test
    void slotOfferRepositoryRoundTrip() {
        Appointment sourceAppointment = saveAppointment(
                "2031-05-10T14:00:00Z", "2031-05-10T15:00:00Z");
        RecoveryJob recoveryJob = new RecoveryJob();
        recoveryJob.setSourceAppointmentId(sourceAppointment.getId());
        recoveryJob.setStatus(RecoveryJobStatus.OPEN);
        RecoveryJob savedRecoveryJob = recoveryJobRepository.saveAndFlush(recoveryJob);

        Appointment currentAppointment = saveAppointment(
                "2031-05-20T14:00:00Z", "2031-05-20T15:00:00Z");
        WaitlistEntry waitlistEntry = new WaitlistEntry();
        waitlistEntry.setPatientId(currentAppointment.getPatientId());
        waitlistEntry.setCurrentAppointmentId(currentAppointment.getId());
        waitlistEntry.setAppointmentTypeId(currentAppointment.getAppointmentTypeId());
        waitlistEntry.setEarliestAppointmentDate(LocalDate.of(2031, 5, 1));
        waitlistEntry.setLatestAppointmentDate(LocalDate.of(2031, 5, 19));
        waitlistEntry.setPreferredTimeOfDay(TimeOfDayPreference.ANY);
        waitlistEntry.setStatus(WaitlistEntryStatus.ACTIVE);
        WaitlistEntry savedWaitlistEntry = waitlistEntryRepository.saveAndFlush(waitlistEntry);

        SlotOffer slotOffer = new SlotOffer();
        slotOffer.setRecoveryJobId(savedRecoveryJob.getId());
        slotOffer.setWaitlistEntryId(savedWaitlistEntry.getId());
        slotOffer.setStatus(SlotOfferStatus.ACCEPTED);
        slotOffer.setExpiresAt(Instant.parse("2031-05-10T14:10:00Z"));
        slotOffer.setAcceptedAt(Instant.parse("2031-05-10T14:05:00Z"));

        SlotOffer saved = slotOfferRepository.saveAndFlush(slotOffer);
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        entityManager.clear();
        SlotOffer retrieved = slotOfferRepository.findById(saved.getId()).orElseThrow();

        assertEquals(saved.getId(), retrieved.getId());
        assertEquals(saved.getRecoveryJobId(), retrieved.getRecoveryJobId());
        assertEquals(saved.getWaitlistEntryId(), retrieved.getWaitlistEntryId());
        assertEquals(saved.getStatus(), retrieved.getStatus());
        assertEquals(saved.getCreatedAt(), retrieved.getCreatedAt());
        assertEquals(saved.getExpiresAt(), retrieved.getExpiresAt());
        assertEquals(saved.getAcceptedAt(), retrieved.getAcceptedAt());
        assertEquals(saved.getUpdatedAt(), retrieved.getUpdatedAt());
    }

    @Test
    void schedulingPolicyRepositoryRoundTrip() {
        SchedulingPolicy policy = schedulingPolicyRepository.findAll().stream()
                .findFirst()
                .orElseThrow();
        policy.setMinimumRecoveryLeadMinutes(45);
        policy.setOfferDurationMinutes(15);

        SchedulingPolicy saved = schedulingPolicyRepository.saveAndFlush(policy);
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        entityManager.clear();
        SchedulingPolicy retrieved = schedulingPolicyRepository.findById(saved.getId()).orElseThrow();

        assertEquals(saved.getId(), retrieved.getId());
        assertEquals(
                saved.getMinimumRecoveryLeadMinutes(), retrieved.getMinimumRecoveryLeadMinutes());
        assertEquals(saved.getOfferDurationMinutes(), retrieved.getOfferDurationMinutes());
        assertEquals(saved.getCreatedAt(), retrieved.getCreatedAt());
        assertEquals(saved.getUpdatedAt(), retrieved.getUpdatedAt());
    }

    @Test
    void auditLogRepositoryRoundTripPreservesJsonbText() {
        User actor = savePatientUser();
        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType("APPOINTMENT");
        auditLog.setEntityId(42L);
        auditLog.setAction("CANCEL");
        auditLog.setOldValues("{\"status\": \"SCHEDULED\"}");
        auditLog.setNewValues("{\"status\": \"CANCELLED\"}");
        auditLog.setActorType(ActorType.USER);
        auditLog.setActorUserId(actor.getId());
        auditLog.setReason("Patient requested cancellation");

        AuditLog saved = auditLogRepository.saveAndFlush(auditLog);
        assertNotNull(saved.getId());
        assertNotNull(saved.getPerformedAt());

        entityManager.clear();
        AuditLog retrieved = auditLogRepository.findById(saved.getId()).orElseThrow();

        assertEquals(saved.getId(), retrieved.getId());
        assertEquals(saved.getEntityType(), retrieved.getEntityType());
        assertEquals(saved.getEntityId(), retrieved.getEntityId());
        assertEquals(saved.getAction(), retrieved.getAction());
        assertEquals(saved.getOldValues(), retrieved.getOldValues());
        assertEquals(saved.getNewValues(), retrieved.getNewValues());
        assertEquals(saved.getActorType(), retrieved.getActorType());
        assertEquals(saved.getActorUserId(), retrieved.getActorUserId());
        assertEquals(saved.getReason(), retrieved.getReason());
        assertEquals(saved.getPerformedAt(), retrieved.getPerformedAt());
    }

    @Test
    void appointmentCancellationReasonMismatchIsRejectedByDatabase() {
        Appointment appointment = newAppointment(
                "2031-06-10T14:00:00Z", "2031-06-10T15:00:00Z");
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment.setCancellationReason(CancellationReason.PATIENT_CANCELLED);

        assertThrows(DataIntegrityViolationException.class,
                () -> appointmentRepository.saveAndFlush(appointment));
    }

    @Test
    void duplicateUserEmailIsRejected() {
        String duplicateEmail = uniqueValue("duplicate") + "@example.com";
        User firstUser = new User();
        firstUser.setEmail(duplicateEmail);
        firstUser.setPasswordHash("first-hash");
        firstUser.setRole(UserRole.PATIENT);
        userRepository.saveAndFlush(firstUser);

        User secondUser = new User();
        secondUser.setEmail(duplicateEmail);
        secondUser.setPasswordHash("second-hash");
        secondUser.setRole(UserRole.PATIENT);

        assertThrows(DataIntegrityViolationException.class,
                () -> userRepository.saveAndFlush(secondUser));
    }

    private Specialty saveSpecialty() {
        Specialty specialty = new Specialty();
        specialty.setName(uniqueValue("Specialty"));
        specialty.setDescription("Test specialty");
        return specialtyRepository.saveAndFlush(specialty);
    }

    private User saveProviderUser() {
        User user = new User();
        user.setEmail(uniqueValue("provider") + "@example.com");
        user.setPasswordHash("provider-password-hash");
        user.setRole(UserRole.PROVIDER);
        user.setDisplayName("Test Provider");
        return userRepository.saveAndFlush(user);
    }

    private Provider saveProvider() {
        Specialty specialty = saveSpecialty();
        User providerUser = saveProviderUser();

        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(specialty.getId());
        provider.setLicenseNumber(uniqueValue("LICENSE"));
        provider.setQualifications("Board certified");
        return providerRepository.saveAndFlush(provider);
    }

    private User savePatientUser() {
        User user = new User();
        user.setEmail(uniqueValue("patient") + "@example.com");
        user.setPasswordHash("patient-password-hash");
        user.setRole(UserRole.PATIENT);
        user.setDisplayName("Test Patient");
        return userRepository.saveAndFlush(user);
    }

    private AppointmentType saveAppointmentType(Long specialtyId) {
        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName(uniqueValue("Appointment Type"));
        appointmentType.setDurationMinutes(60);
        appointmentType.setSpecialtyId(specialtyId);
        appointmentType.setDescription("Test appointment type");
        appointmentType.setActive(true);
        return appointmentTypeRepository.saveAndFlush(appointmentType);
    }

    private Appointment saveAppointment(String startAt, String endAt) {
        return appointmentRepository.saveAndFlush(newAppointment(startAt, endAt));
    }

    private Appointment newAppointment(String startAt, String endAt) {
        User patient = savePatientUser();
        Provider provider = saveProvider();
        AppointmentType appointmentType = saveAppointmentType(provider.getSpecialtyId());

        Appointment appointment = new Appointment();
        appointment.setPatientId(patient.getId());
        appointment.setProviderId(provider.getId());
        appointment.setAppointmentTypeId(appointmentType.getId());
        appointment.setStartAt(Instant.parse(startAt));
        appointment.setEndAt(Instant.parse(endAt));
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        return appointment;
    }

    private static String uniqueValue(String prefix) {
        return prefix + "-" + UNIQUE_SEQUENCE.incrementAndGet();
    }
}

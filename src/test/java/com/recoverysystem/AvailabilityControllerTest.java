package com.recoverysystem;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.ProviderSchedule;
import com.recoverysystem.domain.entity.ProviderUnavailability;
import com.recoverysystem.domain.entity.Specialty;
import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderScheduleRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.service.RecoveryWorkerService;
import com.recoverysystem.service.SlotOfferExpiryWorkerService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AvailabilityControllerTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ZoneId CLINIC_TIME_ZONE = ZoneId.of("America/New_York");
    private static final LocalDate AVAILABILITY_DATE = LocalDate.of(2040, 1, 9);

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
    private MockMvc mockMvc;

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
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private RecoveryWorkerService recoveryWorkerService;

    @MockBean
    private SlotOfferExpiryWorkerService slotOfferExpiryWorkerService;

    @BeforeEach
    void clearWorkflowData() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_log, slot_offer, recovery_job, "
                + "waitlist_entry, appointment, provider_schedule, provider_unavailability, "
                + "provider, appointment_type, specialty, users RESTART IDENTITY CASCADE");
    }

    @Test
    void clearWorkingHoursReturnEveryDurationSizedSlot() throws Exception {
        AvailabilityFixture fixture = createFixture(true);
        User patient = saveUser(UserRole.PATIENT, "Patient");

        performAvailability(patient, fixture.provider().getId(), fixture.appointmentType().getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].startAt").value(atClinicTime(9, 0).toString()))
                .andExpect(jsonPath("$[0].endAt").value(atClinicTime(10, 0).toString()))
                .andExpect(jsonPath("$[1].startAt").value(atClinicTime(10, 0).toString()))
                .andExpect(jsonPath("$[2].startAt").value(atClinicTime(11, 0).toString()))
                .andExpect(jsonPath("$[3].startAt").value(atClinicTime(12, 0).toString()))
                .andExpect(jsonPath("$[3].endAt").value(atClinicTime(13, 0).toString()));
    }

    @Test
    void scheduledAppointmentExcludesOnlyItsOverlappingSlot() throws Exception {
        AvailabilityFixture fixture = createFixture(true);
        saveScheduledAppointment(fixture, atClinicTime(11, 0), atClinicTime(12, 0));
        User patient = saveUser(UserRole.PATIENT, "Patient");

        performAvailability(patient, fixture.provider().getId(), fixture.appointmentType().getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].startAt").value(atClinicTime(9, 0).toString()))
                .andExpect(jsonPath("$[1].startAt").value(atClinicTime(10, 0).toString()))
                .andExpect(jsonPath("$[2].startAt").value(atClinicTime(12, 0).toString()));
    }

    @Test
    void activeBlockExcludesEveryOverlappingSlot() throws Exception {
        AvailabilityFixture fixture = createFixture(true);
        saveBlock(
                fixture.provider(),
                atClinicTime(10, 30),
                atClinicTime(11, 30),
                ProviderUnavailabilityStatus.ACTIVE);
        User patient = saveUser(UserRole.PATIENT, "Patient");

        performAvailability(patient, fixture.provider().getId(), fixture.appointmentType().getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].startAt").value(atClinicTime(9, 0).toString()))
                .andExpect(jsonPath("$[1].startAt").value(atClinicTime(12, 0).toString()));
    }

    @Test
    void pendingBlockAlsoExcludesItsOverlappingSlot() throws Exception {
        AvailabilityFixture fixture = createFixture(true);
        saveBlock(
                fixture.provider(),
                atClinicTime(11, 0),
                atClinicTime(12, 0),
                ProviderUnavailabilityStatus.PENDING);
        User patient = saveUser(UserRole.PATIENT, "Patient");

        performAvailability(patient, fixture.provider().getId(), fixture.appointmentType().getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].startAt").value(atClinicTime(9, 0).toString()))
                .andExpect(jsonPath("$[1].startAt").value(atClinicTime(10, 0).toString()))
                .andExpect(jsonPath("$[2].startAt").value(atClinicTime(12, 0).toString()));
    }

    @Test
    void dateWithoutWorkingHoursReturnsEmptyList() throws Exception {
        AvailabilityFixture fixture = createFixture(false);
        User patient = saveUser(UserRole.PATIENT, "Patient");

        performAvailability(patient, fixture.provider().getId(), fixture.appointmentType().getId())
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void nonexistentProviderOrAppointmentTypeUsesExistingNotFoundMappings() throws Exception {
        AvailabilityFixture fixture = createFixture(true);
        User patient = saveUser(UserRole.PATIENT, "Patient");

        performAvailability(patient, 999_999L, fixture.appointmentType().getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROVIDER_NOT_FOUND"));
        performAvailability(patient, fixture.provider().getId(), 999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_TYPE_NOT_FOUND"));
    }

    @Test
    void providerRoleCannotQueryAvailability() throws Exception {
        AvailabilityFixture fixture = createFixture(true);

        performAvailability(
                        fixture.providerUser(),
                        fixture.provider().getId(),
                        fixture.appointmentType().getId())
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedAvailabilityRequestIsUnauthorized() throws Exception {
        AvailabilityFixture fixture = createFixture(true);

        mockMvc.perform(get("/api/availability")
                        .param("providerId", fixture.provider().getId().toString())
                        .param("appointmentTypeId", fixture.appointmentType().getId().toString())
                        .param("date", AVAILABILITY_DATE.toString()))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions performAvailability(
            User caller, Long providerId, Long appointmentTypeId) throws Exception {
        return mockMvc.perform(get("/api/availability")
                .with(user(new AuthenticatedUser(caller)))
                .param("providerId", providerId.toString())
                .param("appointmentTypeId", appointmentTypeId.toString())
                .param("date", AVAILABILITY_DATE.toString()));
    }

    private AvailabilityFixture createFixture(boolean withSchedule) {
        long number = SEQUENCE.incrementAndGet();
        Specialty specialty = new Specialty();
        specialty.setName("Availability Specialty " + number);
        Specialty savedSpecialty = specialtyRepository.saveAndFlush(specialty);

        User providerUser = saveUser(UserRole.PROVIDER, "Provider");
        Provider provider = new Provider();
        provider.setUserId(providerUser.getId());
        provider.setSpecialtyId(savedSpecialty.getId());
        provider.setLicenseNumber("AVAILABILITY-" + number);
        Provider savedProvider = providerRepository.saveAndFlush(provider);

        AppointmentType appointmentType = new AppointmentType();
        appointmentType.setName("Availability Type " + number);
        appointmentType.setDurationMinutes(60);
        appointmentType.setSpecialtyId(savedSpecialty.getId());
        appointmentType.setActive(true);
        AppointmentType savedAppointmentType =
                appointmentTypeRepository.saveAndFlush(appointmentType);

        if (withSchedule) {
            ProviderSchedule schedule = new ProviderSchedule();
            schedule.setProviderId(savedProvider.getId());
            schedule.setDayOfWeek(AVAILABILITY_DATE.getDayOfWeek());
            schedule.setStartTime(LocalTime.of(9, 0));
            schedule.setEndTime(LocalTime.of(13, 0));
            schedule.setActive(true);
            providerScheduleRepository.saveAndFlush(schedule);
        }

        return new AvailabilityFixture(
                providerUser, savedProvider, savedAppointmentType);
    }

    private Appointment saveScheduledAppointment(
            AvailabilityFixture fixture, Instant startAt, Instant endAt) {
        User patient = saveUser(UserRole.PATIENT, "Booked Patient");
        Appointment appointment = new Appointment();
        appointment.setPatientId(patient.getId());
        appointment.setProviderId(fixture.provider().getId());
        appointment.setAppointmentTypeId(fixture.appointmentType().getId());
        appointment.setStartAt(startAt);
        appointment.setEndAt(endAt);
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        return appointmentRepository.saveAndFlush(appointment);
    }

    private ProviderUnavailability saveBlock(
            Provider provider,
            Instant startAt,
            Instant endAt,
            ProviderUnavailabilityStatus status) {
        ProviderUnavailability block = new ProviderUnavailability();
        block.setProviderId(provider.getId());
        block.setStartAt(startAt);
        block.setEndAt(endAt);
        block.setStatus(status);
        block.setReason("Availability test block");
        if (status == ProviderUnavailabilityStatus.ACTIVE) {
            block.setActivatedAt(Instant.now());
        }
        return providerUnavailabilityRepository.saveAndFlush(block);
    }

    private User saveUser(UserRole role, String displayName) {
        long number = SEQUENCE.incrementAndGet();
        User user = new User();
        user.setEmail("availability-" + role.name().toLowerCase()
                + "-" + number + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(role);
        user.setDisplayName(displayName);
        return userRepository.saveAndFlush(user);
    }

    private static Instant atClinicTime(int hour, int minute) {
        return AVAILABILITY_DATE.atTime(hour, minute)
                .atZone(CLINIC_TIME_ZONE)
                .toInstant();
    }

    private record AvailabilityFixture(
            User providerUser,
            Provider provider,
            AppointmentType appointmentType) {
    }
}

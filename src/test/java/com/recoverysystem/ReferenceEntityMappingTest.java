package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.ProviderSchedule;
import com.recoverysystem.domain.entity.Specialty;
import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderScheduleRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
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

    private static String uniqueValue(String prefix) {
        return prefix + "-" + UNIQUE_SEQUENCE.incrementAndGet();
    }
}

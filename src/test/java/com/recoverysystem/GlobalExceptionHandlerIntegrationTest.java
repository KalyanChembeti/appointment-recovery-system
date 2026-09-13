package com.recoverysystem;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.CancellationReason;
import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.exception.AppointmentNotFoundException;
import com.recoverysystem.exception.AppointmentNotScheduledException;
import com.recoverysystem.exception.AppointmentNotYetStartedException;
import com.recoverysystem.exception.AppointmentTypeNotFoundException;
import com.recoverysystem.exception.AppointmentTypeSpecialtyMismatchException;
import com.recoverysystem.exception.DuplicateEmailException;
import com.recoverysystem.exception.InvalidBlockIntervalException;
import com.recoverysystem.exception.InvalidCancellationReasonException;
import com.recoverysystem.exception.InvalidWaitlistDateRangeException;
import com.recoverysystem.exception.OfferAcceptanceOwnershipException;
import com.recoverysystem.exception.OfferAlreadyAcceptedException;
import com.recoverysystem.exception.OfferAlreadyResolvedException;
import com.recoverysystem.exception.OfferExpiredException;
import com.recoverysystem.exception.PatientDoubleBookedException;
import com.recoverysystem.exception.PreferredProviderSpecialtyMismatchException;
import com.recoverysystem.exception.ProviderBlockConflictsUnresolvedException;
import com.recoverysystem.exception.ProviderBlockNotPendingException;
import com.recoverysystem.exception.ProviderDoubleBookedException;
import com.recoverysystem.exception.ProviderIntervalOccupiedException;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.exception.ProviderUnavailabilityNotFoundException;
import com.recoverysystem.exception.ProviderUnavailableException;
import com.recoverysystem.exception.RecoveryJobNotFoundException;
import com.recoverysystem.exception.RecoveryJobNotOpenException;
import com.recoverysystem.exception.SiblingAlreadyFulfilledException;
import com.recoverysystem.exception.SlotOfferNotFoundException;
import com.recoverysystem.exception.SlotOfferNotOfferedException;
import com.recoverysystem.exception.WaitlistAnchorNotScheduledException;
import com.recoverysystem.exception.WaitlistAnchorOwnershipException;
import com.recoverysystem.exception.WaitlistAppointmentTypeMismatchException;
import com.recoverysystem.exception.WaitlistEntryAnchorMismatchException;
import com.recoverysystem.exception.WaitlistEntryNotActiveException;
import com.recoverysystem.exception.WaitlistEntryNotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(GlobalExceptionHandlerIntegrationTest.ExceptionProbeTestConfiguration.class)
class GlobalExceptionHandlerIntegrationTest {

    private static final Instant INTERVAL_START = Instant.parse("2030-01-01T10:00:00Z");
    private static final Instant INTERVAL_END = Instant.parse("2030-01-01T11:00:00Z");

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

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("exceptionMappings")
    void globalAdviceMapsEveryEnumeratedException(
            String exceptionCode, HttpStatus expectedStatus, String expectedMessage)
            throws Exception {
        performThrow(exceptionCode)
                .andExpect(status().is(expectedStatus.value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(4)))
                .andExpect(jsonPath("$.code").value(exceptionCode))
                .andExpect(jsonPath("$.message").value(expectedMessage))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.path").value(pathFor(exceptionCode)));
    }

    @Test
    void expiredSlotOfferNotOfferedUsesGoneInsteadOfConflict() throws Exception {
        String exceptionCode = "SLOT_OFFER_NOT_OFFERED";

        performThrow(exceptionCode, SlotOfferStatus.EXPIRED)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$", aMapWithSize(4)))
                .andExpect(jsonPath("$.code").value(exceptionCode))
                .andExpect(jsonPath("$.message")
                        .value("Slot offer 1 must be OFFERED but was EXPIRED"))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.path").value(pathFor(exceptionCode)));
    }

    @Test
    void illegalStateResponseDoesNotExposeInternalMessage() throws Exception {
        performThrow("ILLEGAL_STATE")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE"))
                .andExpect(jsonPath("$.message").value("An internal error occurred"))
                .andExpect(content().string(not(containsString("internal test detail"))));
    }

    private org.springframework.test.web.servlet.ResultActions performThrow(String exceptionCode)
            throws Exception {
        return mockMvc.perform(post(pathFor(exceptionCode)).with(csrf()));
    }

    private org.springframework.test.web.servlet.ResultActions performThrow(
            String exceptionCode, SlotOfferStatus actualStatus) throws Exception {
        MockHttpServletRequestBuilder request = post(pathFor(exceptionCode))
                .param("actualStatus", actualStatus.name())
                .with(csrf());
        return mockMvc.perform(request);
    }

    private static String pathFor(String exceptionCode) {
        return "/api/test/throw/" + exceptionCode;
    }

    private static Stream<Arguments> exceptionMappings() {
        return Stream.of(
                arguments("APPOINTMENT_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "Appointment not found: 1"),
                arguments("APPOINTMENT_NOT_SCHEDULED", HttpStatus.CONFLICT,
                        "Appointment 1 must be SCHEDULED but was CANCELLED"),
                arguments("APPOINTMENT_NOT_YET_STARTED", HttpStatus.CONFLICT,
                        "Appointment 1 has not started yet; scheduled start is " + INTERVAL_START),
                arguments("APPOINTMENT_TYPE_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "Appointment type not found: 2"),
                arguments("APPOINTMENT_TYPE_SPECIALTY_MISMATCH", HttpStatus.BAD_REQUEST,
                        "Appointment type 2 has specialty 3, which does not match provider 4 specialty 5"),
                arguments("DUPLICATE_EMAIL", HttpStatus.CONFLICT,
                        "A user with email patient@example.com already exists"),
                arguments("INVALID_BLOCK_INTERVAL", HttpStatus.BAD_REQUEST,
                        "Provider block start must be before end: startAt=" + INTERVAL_END
                                + ", endAt=" + INTERVAL_START),
                arguments("INVALID_CANCELLATION_REASON", HttpStatus.BAD_REQUEST,
                        "Appointment 1 cannot be normally cancelled with reason RESCHEDULED"),
                arguments("INVALID_WAITLIST_DATE_RANGE", HttpStatus.BAD_REQUEST,
                        "Waitlist earliest date 2030-01-02 must not be after latest date 2030-01-01"),
                arguments("OFFER_ACCEPTANCE_OWNERSHIP", HttpStatus.FORBIDDEN,
                        "Slot offer 1 belongs to patient 3, not patient 2"),
                arguments("OFFER_ALREADY_ACCEPTED", HttpStatus.CONFLICT,
                        "Slot offer 1 has already been accepted"),
                arguments("OFFER_ALREADY_RESOLVED", HttpStatus.CONFLICT,
                        "Slot offer 1 has already been resolved with status DECLINED"),
                arguments("OFFER_EXPIRED", HttpStatus.GONE, "Slot offer 1 has expired"),
                arguments("PATIENT_DOUBLE_BOOKED", HttpStatus.CONFLICT,
                        "The patient already has a scheduled appointment during the requested interval"),
                arguments("PREFERRED_PROVIDER_SPECIALTY_MISMATCH", HttpStatus.BAD_REQUEST,
                        "Preferred provider 1 has specialty 2, which does not match appointment type 3 specialty 4"),
                arguments("PROVIDER_BLOCK_CONFLICTS_UNRESOLVED", HttpStatus.CONFLICT,
                        "Provider unavailability 1 for provider 2 still conflicts with appointments [3, 4]"),
                arguments("PROVIDER_BLOCK_NOT_PENDING", HttpStatus.CONFLICT,
                        "Provider unavailability 1 must be PENDING but was ACTIVE"),
                arguments("PROVIDER_DOUBLE_BOOKED", HttpStatus.CONFLICT,
                        "The provider already has a scheduled appointment during the requested interval"),
                arguments("PROVIDER_INTERVAL_OCCUPIED", HttpStatus.CONFLICT,
                        "Provider 1 already has a scheduled appointment from " + INTERVAL_START
                                + " to " + INTERVAL_END),
                arguments("PROVIDER_NOT_FOUND", HttpStatus.NOT_FOUND, "Provider not found: 1"),
                arguments("PROVIDER_UNAVAILABILITY_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "Provider unavailability not found: 1"),
                arguments("PROVIDER_UNAVAILABLE", HttpStatus.CONFLICT,
                        "Provider 1 is unavailable from " + INTERVAL_START + " to " + INTERVAL_END),
                arguments("RECOVERY_JOB_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "Recovery job not found: 1"),
                arguments("RECOVERY_JOB_NOT_OPEN", HttpStatus.CONFLICT,
                        "Recovery job 1 must be OPEN but was FILLED"),
                arguments("SIBLING_ALREADY_FULFILLED", HttpStatus.INTERNAL_SERVER_ERROR,
                        "Appointment 1 already has fulfilled waitlist entries [2]"),
                arguments("SLOT_OFFER_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "Slot offer not found: 1"),
                arguments("SLOT_OFFER_NOT_OFFERED", HttpStatus.CONFLICT,
                        "Slot offer 1 must be OFFERED but was DECLINED"),
                arguments("WAITLIST_ANCHOR_NOT_SCHEDULED", HttpStatus.CONFLICT,
                        "Waitlist anchor appointment 1 must be SCHEDULED but was CANCELLED"),
                arguments("WAITLIST_ANCHOR_OWNERSHIP", HttpStatus.FORBIDDEN,
                        "Waitlist anchor appointment 1 belongs to patient 3, not patient 2"),
                arguments("WAITLIST_APPOINTMENT_TYPE_MISMATCH", HttpStatus.BAD_REQUEST,
                        "Requested appointment type 2 does not match waitlist anchor appointment 1 type 3"),
                arguments("WAITLIST_ENTRY_ANCHOR_MISMATCH", HttpStatus.INTERNAL_SERVER_ERROR,
                        "Waitlist entry 1 changed anchor appointment from 2 to 3 while being modified"),
                arguments("WAITLIST_ENTRY_NOT_ACTIVE", HttpStatus.CONFLICT,
                        "Waitlist entry 1 must be ACTIVE but was REMOVED"),
                arguments("WAITLIST_ENTRY_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "Waitlist entry not found: 1"),
                arguments("ILLEGAL_STATE", HttpStatus.INTERNAL_SERVER_ERROR,
                        "An internal error occurred"));
    }

    private static Arguments arguments(
            String exceptionCode, HttpStatus status, String expectedMessage) {
        return Arguments.of(exceptionCode, status, expectedMessage);
    }

    @RestController
    static class ExceptionProbeController {

        @PostMapping("/api/test/throw/{exceptionCode}")
        void throwException(
                @PathVariable String exceptionCode,
                @RequestParam(defaultValue = "DECLINED") SlotOfferStatus actualStatus) {
            throw exceptionFor(exceptionCode, actualStatus);
        }

        private RuntimeException exceptionFor(
                String exceptionCode, SlotOfferStatus actualStatus) {
            return switch (exceptionCode) {
                case "APPOINTMENT_NOT_FOUND" -> new AppointmentNotFoundException(1L);
                case "APPOINTMENT_NOT_SCHEDULED" ->
                        new AppointmentNotScheduledException(1L, AppointmentStatus.CANCELLED);
                case "APPOINTMENT_NOT_YET_STARTED" ->
                        new AppointmentNotYetStartedException(1L, INTERVAL_START);
                case "APPOINTMENT_TYPE_NOT_FOUND" -> new AppointmentTypeNotFoundException(2L);
                case "APPOINTMENT_TYPE_SPECIALTY_MISMATCH" ->
                        new AppointmentTypeSpecialtyMismatchException(2L, 3L, 4L, 5L);
                case "DUPLICATE_EMAIL" -> new DuplicateEmailException("patient@example.com");
                case "INVALID_BLOCK_INTERVAL" ->
                        new InvalidBlockIntervalException(INTERVAL_END, INTERVAL_START);
                case "INVALID_CANCELLATION_REASON" ->
                        new InvalidCancellationReasonException(1L, CancellationReason.RESCHEDULED);
                case "INVALID_WAITLIST_DATE_RANGE" -> new InvalidWaitlistDateRangeException(
                        LocalDate.of(2030, 1, 2), LocalDate.of(2030, 1, 1));
                case "OFFER_ACCEPTANCE_OWNERSHIP" ->
                        new OfferAcceptanceOwnershipException(1L, 2L, 3L);
                case "OFFER_ALREADY_ACCEPTED" -> new OfferAlreadyAcceptedException(1L);
                case "OFFER_ALREADY_RESOLVED" ->
                        new OfferAlreadyResolvedException(1L, SlotOfferStatus.DECLINED);
                case "OFFER_EXPIRED" -> new OfferExpiredException(1L);
                case "PATIENT_DOUBLE_BOOKED" ->
                        new PatientDoubleBookedException(new RuntimeException("constraint detail"));
                case "PREFERRED_PROVIDER_SPECIALTY_MISMATCH" ->
                        new PreferredProviderSpecialtyMismatchException(1L, 2L, 3L, 4L);
                case "PROVIDER_BLOCK_CONFLICTS_UNRESOLVED" ->
                        new ProviderBlockConflictsUnresolvedException(1L, 2L, List.of(3L, 4L));
                case "PROVIDER_BLOCK_NOT_PENDING" ->
                        new ProviderBlockNotPendingException(
                                1L, ProviderUnavailabilityStatus.ACTIVE);
                case "PROVIDER_DOUBLE_BOOKED" ->
                        new ProviderDoubleBookedException(new RuntimeException("constraint detail"));
                case "PROVIDER_INTERVAL_OCCUPIED" ->
                        new ProviderIntervalOccupiedException(1L, INTERVAL_START, INTERVAL_END);
                case "PROVIDER_NOT_FOUND" -> new ProviderNotFoundException(1L);
                case "PROVIDER_UNAVAILABILITY_NOT_FOUND" ->
                        new ProviderUnavailabilityNotFoundException(1L);
                case "PROVIDER_UNAVAILABLE" ->
                        new ProviderUnavailableException(1L, INTERVAL_START, INTERVAL_END);
                case "RECOVERY_JOB_NOT_FOUND" -> new RecoveryJobNotFoundException(1L);
                case "RECOVERY_JOB_NOT_OPEN" ->
                        new RecoveryJobNotOpenException(1L, RecoveryJobStatus.FILLED);
                case "SIBLING_ALREADY_FULFILLED" ->
                        new SiblingAlreadyFulfilledException(1L, List.of(2L));
                case "SLOT_OFFER_NOT_FOUND" -> new SlotOfferNotFoundException(1L);
                case "SLOT_OFFER_NOT_OFFERED" ->
                        new SlotOfferNotOfferedException(1L, actualStatus);
                case "WAITLIST_ANCHOR_NOT_SCHEDULED" ->
                        new WaitlistAnchorNotScheduledException(1L, AppointmentStatus.CANCELLED);
                case "WAITLIST_ANCHOR_OWNERSHIP" ->
                        new WaitlistAnchorOwnershipException(1L, 2L, 3L);
                case "WAITLIST_APPOINTMENT_TYPE_MISMATCH" ->
                        new WaitlistAppointmentTypeMismatchException(1L, 2L, 3L);
                case "WAITLIST_ENTRY_ANCHOR_MISMATCH" ->
                        new WaitlistEntryAnchorMismatchException(1L, 2L, 3L);
                case "WAITLIST_ENTRY_NOT_ACTIVE" ->
                        new WaitlistEntryNotActiveException(1L, WaitlistEntryStatus.REMOVED);
                case "WAITLIST_ENTRY_NOT_FOUND" -> new WaitlistEntryNotFoundException(1L);
                case "ILLEGAL_STATE" -> new IllegalStateException("internal test detail");
                default -> throw new IllegalArgumentException(
                        "Unknown exception code: " + exceptionCode);
            };
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExceptionProbeTestConfiguration {

        @Bean
        ExceptionProbeController exceptionProbeController() {
            return new ExceptionProbeController();
        }
    }
}

package com.recoverysystem.web.exception;

import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.exception.AppointmentNotFoundException;
import com.recoverysystem.exception.AppointmentNotScheduledException;
import com.recoverysystem.exception.AppointmentNotYetStartedException;
import com.recoverysystem.exception.AppointmentOwnershipException;
import com.recoverysystem.exception.AppointmentTypeNotFoundException;
import com.recoverysystem.exception.AppointmentTypeSpecialtyMismatchException;
import com.recoverysystem.exception.DuplicateEmailException;
import com.recoverysystem.exception.InvalidBlockIntervalException;
import com.recoverysystem.exception.InvalidCancellationReasonException;
import com.recoverysystem.exception.InvalidWaitlistDateRangeException;
import com.recoverysystem.exception.MissingPatientIdException;
import com.recoverysystem.exception.OfferAcceptanceOwnershipException;
import com.recoverysystem.exception.OfferAlreadyAcceptedException;
import com.recoverysystem.exception.OfferAlreadyResolvedException;
import com.recoverysystem.exception.OfferExpiredException;
import com.recoverysystem.exception.PatientDoubleBookedException;
import com.recoverysystem.exception.PatientIdentityMismatchException;
import com.recoverysystem.exception.PreferredProviderSpecialtyMismatchException;
import com.recoverysystem.exception.ProviderActionNotPermittedException;
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
import com.recoverysystem.exception.WaitlistEntryOwnershipException;
import com.recoverysystem.web.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password";
    private static final String INTERNAL_ERROR_MESSAGE = "An internal error occurred";

    @ExceptionHandler(AppointmentNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleAppointmentNotFound(
            AppointmentNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND", exception, request);
    }

    @ExceptionHandler(AppointmentTypeNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleAppointmentTypeNotFound(
            AppointmentTypeNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "APPOINTMENT_TYPE_NOT_FOUND", exception, request);
    }

    @ExceptionHandler(ProviderNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleProviderNotFound(
            ProviderNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND", exception, request);
    }

    @ExceptionHandler(ProviderUnavailabilityNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleProviderUnavailabilityNotFound(
            ProviderUnavailabilityNotFoundException exception, HttpServletRequest request) {
        return error(
                HttpStatus.NOT_FOUND,
                "PROVIDER_UNAVAILABILITY_NOT_FOUND",
                exception,
                request);
    }

    @ExceptionHandler(RecoveryJobNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleRecoveryJobNotFound(
            RecoveryJobNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "RECOVERY_JOB_NOT_FOUND", exception, request);
    }

    @ExceptionHandler(SlotOfferNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleSlotOfferNotFound(
            SlotOfferNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "SLOT_OFFER_NOT_FOUND", exception, request);
    }

    @ExceptionHandler(WaitlistEntryNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleWaitlistEntryNotFound(
            WaitlistEntryNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "WAITLIST_ENTRY_NOT_FOUND", exception, request);
    }

    @ExceptionHandler(AppointmentNotScheduledException.class)
    ResponseEntity<ApiErrorResponse> handleAppointmentNotScheduled(
            AppointmentNotScheduledException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "APPOINTMENT_NOT_SCHEDULED", exception, request);
    }

    @ExceptionHandler(AppointmentNotYetStartedException.class)
    ResponseEntity<ApiErrorResponse> handleAppointmentNotYetStarted(
            AppointmentNotYetStartedException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "APPOINTMENT_NOT_YET_STARTED", exception, request);
    }

    @ExceptionHandler(DuplicateEmailException.class)
    ResponseEntity<ApiErrorResponse> handleDuplicateEmail(
            DuplicateEmailException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", exception, request);
    }

    @ExceptionHandler(OfferAlreadyAcceptedException.class)
    ResponseEntity<ApiErrorResponse> handleOfferAlreadyAccepted(
            OfferAlreadyAcceptedException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "OFFER_ALREADY_ACCEPTED", exception, request);
    }

    @ExceptionHandler(OfferAlreadyResolvedException.class)
    ResponseEntity<ApiErrorResponse> handleOfferAlreadyResolved(
            OfferAlreadyResolvedException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "OFFER_ALREADY_RESOLVED", exception, request);
    }

    @ExceptionHandler(PatientDoubleBookedException.class)
    ResponseEntity<ApiErrorResponse> handlePatientDoubleBooked(
            PatientDoubleBookedException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "PATIENT_DOUBLE_BOOKED", exception, request);
    }

    @ExceptionHandler(ProviderBlockConflictsUnresolvedException.class)
    ResponseEntity<ApiErrorResponse> handleProviderBlockConflictsUnresolved(
            ProviderBlockConflictsUnresolvedException exception, HttpServletRequest request) {
        return error(
                HttpStatus.CONFLICT,
                "PROVIDER_BLOCK_CONFLICTS_UNRESOLVED",
                exception,
                request);
    }

    @ExceptionHandler(ProviderBlockNotPendingException.class)
    ResponseEntity<ApiErrorResponse> handleProviderBlockNotPending(
            ProviderBlockNotPendingException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "PROVIDER_BLOCK_NOT_PENDING", exception, request);
    }

    @ExceptionHandler(ProviderDoubleBookedException.class)
    ResponseEntity<ApiErrorResponse> handleProviderDoubleBooked(
            ProviderDoubleBookedException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "PROVIDER_DOUBLE_BOOKED", exception, request);
    }

    @ExceptionHandler(ProviderIntervalOccupiedException.class)
    ResponseEntity<ApiErrorResponse> handleProviderIntervalOccupied(
            ProviderIntervalOccupiedException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "PROVIDER_INTERVAL_OCCUPIED", exception, request);
    }

    @ExceptionHandler(ProviderUnavailableException.class)
    ResponseEntity<ApiErrorResponse> handleProviderUnavailable(
            ProviderUnavailableException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "PROVIDER_UNAVAILABLE", exception, request);
    }

    @ExceptionHandler(RecoveryJobNotOpenException.class)
    ResponseEntity<ApiErrorResponse> handleRecoveryJobNotOpen(
            RecoveryJobNotOpenException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "RECOVERY_JOB_NOT_OPEN", exception, request);
    }

    @ExceptionHandler(WaitlistAnchorNotScheduledException.class)
    ResponseEntity<ApiErrorResponse> handleWaitlistAnchorNotScheduled(
            WaitlistAnchorNotScheduledException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "WAITLIST_ANCHOR_NOT_SCHEDULED", exception, request);
    }

    @ExceptionHandler(WaitlistEntryNotActiveException.class)
    ResponseEntity<ApiErrorResponse> handleWaitlistEntryNotActive(
            WaitlistEntryNotActiveException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "WAITLIST_ENTRY_NOT_ACTIVE", exception, request);
    }

    @ExceptionHandler(OfferExpiredException.class)
    ResponseEntity<ApiErrorResponse> handleOfferExpired(
            OfferExpiredException exception, HttpServletRequest request) {
        return error(HttpStatus.GONE, "OFFER_EXPIRED", exception, request);
    }

    @ExceptionHandler(SlotOfferNotOfferedException.class)
    ResponseEntity<ApiErrorResponse> handleSlotOfferNotOffered(
            SlotOfferNotOfferedException exception, HttpServletRequest request) {
        // An EXPIRED offer represents the same permanently unavailable domain fact as
        // OfferExpiredException, so it is 410; every other non-OFFERED state is a 409 conflict.
        HttpStatus status = exception.getActualStatus() == SlotOfferStatus.EXPIRED
                ? HttpStatus.GONE
                : HttpStatus.CONFLICT;
        return error(status, "SLOT_OFFER_NOT_OFFERED", exception, request);
    }

    @ExceptionHandler(AppointmentTypeSpecialtyMismatchException.class)
    ResponseEntity<ApiErrorResponse> handleAppointmentTypeSpecialtyMismatch(
            AppointmentTypeSpecialtyMismatchException exception, HttpServletRequest request) {
        return error(
                HttpStatus.BAD_REQUEST,
                "APPOINTMENT_TYPE_SPECIALTY_MISMATCH",
                exception,
                request);
    }

    @ExceptionHandler(InvalidBlockIntervalException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidBlockInterval(
            InvalidBlockIntervalException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_BLOCK_INTERVAL", exception, request);
    }

    @ExceptionHandler(InvalidCancellationReasonException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidCancellationReason(
            InvalidCancellationReasonException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_CANCELLATION_REASON", exception, request);
    }

    @ExceptionHandler(InvalidWaitlistDateRangeException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidWaitlistDateRange(
            InvalidWaitlistDateRangeException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_WAITLIST_DATE_RANGE", exception, request);
    }

    @ExceptionHandler(PreferredProviderSpecialtyMismatchException.class)
    ResponseEntity<ApiErrorResponse> handlePreferredProviderSpecialtyMismatch(
            PreferredProviderSpecialtyMismatchException exception, HttpServletRequest request) {
        return error(
                HttpStatus.BAD_REQUEST,
                "PREFERRED_PROVIDER_SPECIALTY_MISMATCH",
                exception,
                request);
    }

    @ExceptionHandler(WaitlistAppointmentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> handleWaitlistAppointmentTypeMismatch(
            WaitlistAppointmentTypeMismatchException exception, HttpServletRequest request) {
        return error(
                HttpStatus.BAD_REQUEST,
                "WAITLIST_APPOINTMENT_TYPE_MISMATCH",
                exception,
                request);
    }

    @ExceptionHandler(MissingPatientIdException.class)
    ResponseEntity<ApiErrorResponse> handleMissingPatientId(
            MissingPatientIdException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "MISSING_PATIENT_ID", exception, request);
    }

    @ExceptionHandler(OfferAcceptanceOwnershipException.class)
    ResponseEntity<ApiErrorResponse> handleOfferAcceptanceOwnership(
            OfferAcceptanceOwnershipException exception, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "OFFER_ACCEPTANCE_OWNERSHIP", exception, request);
    }

    @ExceptionHandler(AppointmentOwnershipException.class)
    ResponseEntity<ApiErrorResponse> handleAppointmentOwnership(
            AppointmentOwnershipException exception, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "APPOINTMENT_OWNERSHIP", exception, request);
    }

    @ExceptionHandler(WaitlistAnchorOwnershipException.class)
    ResponseEntity<ApiErrorResponse> handleWaitlistAnchorOwnership(
            WaitlistAnchorOwnershipException exception, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "WAITLIST_ANCHOR_OWNERSHIP", exception, request);
    }

    @ExceptionHandler(WaitlistEntryOwnershipException.class)
    ResponseEntity<ApiErrorResponse> handleWaitlistEntryOwnership(
            WaitlistEntryOwnershipException exception, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "WAITLIST_ENTRY_OWNERSHIP", exception, request);
    }

    @ExceptionHandler(PatientIdentityMismatchException.class)
    ResponseEntity<ApiErrorResponse> handlePatientIdentityMismatch(
            PatientIdentityMismatchException exception, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "PATIENT_IDENTITY_MISMATCH", exception, request);
    }

    @ExceptionHandler(ProviderActionNotPermittedException.class)
    ResponseEntity<ApiErrorResponse> handleProviderActionNotPermitted(
            ProviderActionNotPermittedException exception, HttpServletRequest request) {
        return error(
                HttpStatus.FORBIDDEN,
                "PROVIDER_ACTION_NOT_PERMITTED",
                exception,
                request);
    }

    @ExceptionHandler(SiblingAlreadyFulfilledException.class)
    ResponseEntity<ApiErrorResponse> handleSiblingAlreadyFulfilled(
            SiblingAlreadyFulfilledException exception, HttpServletRequest request) {
        // Every sibling fulfillment requires the same old-Appointment lock, so observing a
        // fulfilled sibling here is a structurally unreachable internal invariant violation.
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "SIBLING_ALREADY_FULFILLED",
                exception,
                request);
    }

    @ExceptionHandler(WaitlistEntryAnchorMismatchException.class)
    ResponseEntity<ApiErrorResponse> handleWaitlistEntryAnchorMismatch(
            WaitlistEntryAnchorMismatchException exception, HttpServletRequest request) {
        // currentAppointmentId is assigned only while constructing a new entry and no workflow
        // mutates it later, so differing routing and locked reads are structurally unreachable.
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "WAITLIST_ENTRY_ANCHOR_MISMATCH",
                exception,
                request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiErrorResponse> handleIllegalState(
            IllegalStateException exception, HttpServletRequest request) {
        LOGGER.error(
                "Internal invariant failure while handling {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "ILLEGAL_STATE",
                INTERNAL_ERROR_MESSAGE,
                request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<Map<String, Object>> handleBadCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", INVALID_CREDENTIALS_MESSAGE));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidationFailure(
            MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(Map.of("message", "Validation failed", "errors", errors));
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            RuntimeException exception,
            HttpServletRequest request) {
        return error(status, code, exception.getMessage(), request);
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(
                        code,
                        message,
                        Instant.now(),
                        request.getRequestURI()));
    }
}

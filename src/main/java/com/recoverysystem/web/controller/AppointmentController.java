package com.recoverysystem.web.controller;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.enums.CancellationReason;
import com.recoverysystem.exception.AppointmentNotFoundException;
import com.recoverysystem.exception.ProviderActionNotPermittedException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.service.AppointmentCancellationService;
import com.recoverysystem.service.AppointmentReschedulingService;
import com.recoverysystem.service.DirectBookingService;
import com.recoverysystem.web.dto.AppointmentResponse;
import com.recoverysystem.web.dto.BookAppointmentRequest;
import com.recoverysystem.web.dto.CancelAppointmentRequest;
import com.recoverysystem.web.dto.RescheduleAppointmentRequest;
import com.recoverysystem.web.security.EffectivePatientIdResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final DirectBookingService directBookingService;
    private final AppointmentCancellationService appointmentCancellationService;
    private final AppointmentReschedulingService appointmentReschedulingService;
    private final AppointmentRepository appointmentRepository;
    private final EffectivePatientIdResolver effectivePatientIdResolver;

    public AppointmentController(
            DirectBookingService directBookingService,
            AppointmentCancellationService appointmentCancellationService,
            AppointmentReschedulingService appointmentReschedulingService,
            AppointmentRepository appointmentRepository,
            EffectivePatientIdResolver effectivePatientIdResolver) {
        this.directBookingService = directBookingService;
        this.appointmentCancellationService = appointmentCancellationService;
        this.appointmentReschedulingService = appointmentReschedulingService;
        this.appointmentRepository = appointmentRepository;
        this.effectivePatientIdResolver = effectivePatientIdResolver;
    }

    @PostMapping
    ResponseEntity<AppointmentResponse> bookAppointment(
            @Valid @RequestBody BookAppointmentRequest request,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        Long effectivePatientId = effectivePatientIdResolver.resolve(
                request.patientId(), authenticatedUser);
        Appointment appointment = directBookingService.bookAppointment(
                effectivePatientId,
                request.providerId(),
                request.appointmentTypeId(),
                request.startAt(),
                authenticatedUser.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppointmentResponse.from(appointment));
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<AppointmentResponse> cancelAppointment(
            @PathVariable Long id,
            @Valid @RequestBody CancelAppointmentRequest request,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        Appointment targetAppointment = findAppointment(id);
        verifyPatientOwnership(targetAppointment, authenticatedUser);

        CancellationReason cancellationReason = cancellationReasonFor(authenticatedUser);
        // reasonText has no destination in the current cancellation service signature.
        Appointment appointment = appointmentCancellationService.cancelAppointment(
                id, cancellationReason, authenticatedUser.getUserId());
        return ResponseEntity.ok(AppointmentResponse.from(appointment));
    }

    @PostMapping("/{id}/reschedule")
    ResponseEntity<AppointmentResponse> rescheduleAppointment(
            @PathVariable Long id,
            @Valid @RequestBody RescheduleAppointmentRequest request,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        Appointment targetAppointment = findAppointment(id);
        verifyPatientOwnership(targetAppointment, authenticatedUser);

        Appointment appointment = appointmentReschedulingService.rescheduleAppointment(
                id,
                request.providerId(),
                request.appointmentTypeId(),
                request.startAt(),
                authenticatedUser.getUserId());
        return ResponseEntity.ok(AppointmentResponse.from(appointment));
    }

    private Appointment findAppointment(Long appointmentId) {
        return appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(appointmentId));
    }

    private void verifyPatientOwnership(
            Appointment appointment, AuthenticatedUser authenticatedUser) {
        switch (authenticatedUser.getRole()) {
            case PATIENT, PROVIDER -> {
                Long effectivePatientId =
                        effectivePatientIdResolver.resolve(null, authenticatedUser);
                effectivePatientIdResolver.verifyOwnership(
                        effectivePatientId, appointment.getPatientId());
            }
            case RECEPTIONIST, ADMIN -> {
                // Staff roles may act on any patient's appointment.
            }
        }
    }

    private CancellationReason cancellationReasonFor(AuthenticatedUser authenticatedUser) {
        return switch (authenticatedUser.getRole()) {
            case PATIENT -> CancellationReason.PATIENT_CANCELLED;
            case RECEPTIONIST, ADMIN -> CancellationReason.STAFF_CANCELLED;
            case PROVIDER ->
                    throw new ProviderActionNotPermittedException(authenticatedUser.getRole());
        };
    }
}

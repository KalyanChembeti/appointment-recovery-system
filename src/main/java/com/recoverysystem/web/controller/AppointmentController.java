package com.recoverysystem.web.controller;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.enums.CancellationReason;
import com.recoverysystem.exception.AppointmentNotFoundException;
import com.recoverysystem.exception.AppointmentOwnershipException;
import com.recoverysystem.exception.ProviderActionNotPermittedException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.service.AppointmentCancellationService;
import com.recoverysystem.service.AppointmentCompletionService;
import com.recoverysystem.service.AppointmentNoShowService;
import com.recoverysystem.service.AppointmentReschedulingService;
import com.recoverysystem.service.DirectBookingService;
import com.recoverysystem.web.dto.AppointmentResponse;
import com.recoverysystem.web.dto.BookAppointmentRequest;
import com.recoverysystem.web.dto.CancelAppointmentRequest;
import com.recoverysystem.web.dto.RescheduleAppointmentRequest;
import com.recoverysystem.web.security.EffectivePatientIdResolver;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final AppointmentCompletionService appointmentCompletionService;
    private final AppointmentNoShowService appointmentNoShowService;
    private final AppointmentReschedulingService appointmentReschedulingService;
    private final AppointmentRepository appointmentRepository;
    private final ProviderRepository providerRepository;
    private final EffectivePatientIdResolver effectivePatientIdResolver;

    public AppointmentController(
            DirectBookingService directBookingService,
            AppointmentCancellationService appointmentCancellationService,
            AppointmentCompletionService appointmentCompletionService,
            AppointmentNoShowService appointmentNoShowService,
            AppointmentReschedulingService appointmentReschedulingService,
            AppointmentRepository appointmentRepository,
            ProviderRepository providerRepository,
            EffectivePatientIdResolver effectivePatientIdResolver) {
        this.directBookingService = directBookingService;
        this.appointmentCancellationService = appointmentCancellationService;
        this.appointmentCompletionService = appointmentCompletionService;
        this.appointmentNoShowService = appointmentNoShowService;
        this.appointmentReschedulingService = appointmentReschedulingService;
        this.appointmentRepository = appointmentRepository;
        this.providerRepository = providerRepository;
        this.effectivePatientIdResolver = effectivePatientIdResolver;
    }

    // Intentional extension beyond the locked API catalog: this collection endpoint provides
    // role-scoped appointment lists that the catalog's single-entity GET does not provide.
    @GetMapping
    ResponseEntity<List<AppointmentResponse>> listAppointments(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        // Deliberately no status filter: the scoped history includes scheduled and resolved
        // appointments because FR-APT-3 does not restrict the listing to active records.
        // The MVP returns the full unbounded scope; pagination can be added when volume warrants it.
        List<AppointmentResponse> response = switch (authenticatedUser.getRole()) {
            case PATIENT -> appointmentRepository.findResponsesByPatientId(
                    authenticatedUser.getUserId());
            case PROVIDER -> appointmentRepository.findResponsesByProviderId(
                    findProvider(authenticatedUser).getId());
            case RECEPTIONIST, ADMIN -> appointmentRepository.findAllResponses();
        };
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    ResponseEntity<AppointmentResponse> getAppointment(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        AppointmentResponse response = findAppointmentResponse(id);
        verifyReadAccess(response, authenticatedUser);
        return ResponseEntity.ok(response);
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
                .body(findAppointmentResponse(appointment.getId()));
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<AppointmentResponse> cancelAppointment(
            @PathVariable Long id,
            @Valid @RequestBody CancelAppointmentRequest request,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        Appointment targetAppointment = findAppointment(id);
        verifyPatientOwnership(targetAppointment, authenticatedUser);

        CancellationReason cancellationReason = cancellationReasonFor(authenticatedUser);
        Appointment appointment = appointmentCancellationService.cancelAppointment(
                id,
                cancellationReason,
                authenticatedUser.getUserId(),
                request.reasonText());
        return ResponseEntity.ok(findAppointmentResponse(appointment.getId()));
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
        return ResponseEntity.ok(findAppointmentResponse(appointment.getId()));
    }

    @PostMapping("/{id}/complete")
    ResponseEntity<AppointmentResponse> completeAppointment(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        Appointment appointment = appointmentCompletionService.completeAppointment(
                id, authenticatedUser.getUserId());
        return ResponseEntity.ok(findAppointmentResponse(appointment.getId()));
    }

    @PostMapping("/{id}/mark-no-show")
    ResponseEntity<AppointmentResponse> markAppointmentNoShow(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        Appointment appointment = appointmentNoShowService.markNoShow(
                id, authenticatedUser.getUserId());
        return ResponseEntity.ok(findAppointmentResponse(appointment.getId()));
    }

    private Appointment findAppointment(Long appointmentId) {
        return appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(appointmentId));
    }

    private AppointmentResponse findAppointmentResponse(Long appointmentId) {
        return appointmentRepository.findResponseById(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(appointmentId));
    }

    private void verifyReadAccess(
            AppointmentResponse appointment, AuthenticatedUser authenticatedUser) {
        switch (authenticatedUser.getRole()) {
            case PATIENT -> effectivePatientIdResolver.verifyOwnership(
                    authenticatedUser.getUserId(), appointment.patientId());
            case PROVIDER -> {
                Long providerId = findProvider(authenticatedUser).getId();
                if (!providerId.equals(appointment.providerId())) {
                    throw AppointmentOwnershipException.forProvider(
                            providerId, appointment.providerId());
                }
            }
            case RECEPTIONIST, ADMIN -> {
                // Staff roles may view any appointment.
            }
        }
    }

    private Provider findProvider(AuthenticatedUser authenticatedUser) {
        return providerRepository.findByUserId(authenticatedUser.getUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated PROVIDER user %d has no corresponding Provider row"
                                .formatted(authenticatedUser.getUserId())));
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

package com.recoverysystem.web.controller;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.service.DirectBookingService;
import com.recoverysystem.web.dto.AppointmentResponse;
import com.recoverysystem.web.dto.BookAppointmentRequest;
import com.recoverysystem.web.security.EffectivePatientIdResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final DirectBookingService directBookingService;
    private final EffectivePatientIdResolver effectivePatientIdResolver;

    public AppointmentController(
            DirectBookingService directBookingService,
            EffectivePatientIdResolver effectivePatientIdResolver) {
        this.directBookingService = directBookingService;
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
}

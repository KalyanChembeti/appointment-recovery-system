package com.recoverysystem.web.dto;

import com.recoverysystem.domain.entity.Appointment;
import java.time.Instant;

public record AppointmentResponse(
        Long id,
        Long patientId,
        Long providerId,
        Long appointmentTypeId,
        Instant startAt,
        Instant endAt,
        String status) {

    public static AppointmentResponse from(Appointment appointment) {
        return new AppointmentResponse(
                appointment.getId(),
                appointment.getPatientId(),
                appointment.getProviderId(),
                appointment.getAppointmentTypeId(),
                appointment.getStartAt(),
                appointment.getEndAt(),
                appointment.getStatus().name());
    }
}

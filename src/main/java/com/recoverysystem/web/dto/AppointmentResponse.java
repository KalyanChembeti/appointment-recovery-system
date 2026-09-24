package com.recoverysystem.web.dto;

import com.recoverysystem.domain.enums.AppointmentStatus;
import java.time.Instant;

public record AppointmentResponse(
        Long id,
        Long patientId,
        Long providerId,
        Long appointmentTypeId,
        Instant startAt,
        Instant endAt,
        String status,
        String patientDisplayName) {

    public AppointmentResponse(
            Long id,
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Instant startAt,
            Instant endAt,
            AppointmentStatus status,
            String patientDisplayName) {
        this(
                id,
                patientId,
                providerId,
                appointmentTypeId,
                startAt,
                endAt,
                status.name(),
                patientDisplayName);
    }
}

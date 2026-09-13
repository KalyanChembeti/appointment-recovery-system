package com.recoverysystem.web.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record BookAppointmentRequest(
        Long patientId,
        @NotNull Long providerId,
        @NotNull Long appointmentTypeId,
        @NotNull Instant startAt) {
}

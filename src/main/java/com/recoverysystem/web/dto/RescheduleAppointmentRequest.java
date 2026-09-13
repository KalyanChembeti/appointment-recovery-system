package com.recoverysystem.web.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record RescheduleAppointmentRequest(
        @NotNull Long providerId,
        @NotNull Long appointmentTypeId,
        @NotNull Instant startAt) {
}

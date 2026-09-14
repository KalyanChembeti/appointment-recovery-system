package com.recoverysystem.web.dto;

import com.recoverysystem.domain.enums.TimeOfDayPreference;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record JoinWaitlistRequest(
        @NotNull Long currentAppointmentId,
        @NotNull Long appointmentTypeId,
        Long providerId,
        @NotNull LocalDate earliestAppointmentDate,
        @NotNull LocalDate latestAppointmentDate,
        TimeOfDayPreference preferredTimeOfDay) {
}

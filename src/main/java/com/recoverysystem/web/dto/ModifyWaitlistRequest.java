package com.recoverysystem.web.dto;

import com.recoverysystem.domain.enums.TimeOfDayPreference;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ModifyWaitlistRequest(
        @NotNull LocalDate newEarliestDate,
        @NotNull LocalDate newLatestDate,
        Long newPreferredProviderId,
        TimeOfDayPreference newPreferredTimeOfDay) {
}

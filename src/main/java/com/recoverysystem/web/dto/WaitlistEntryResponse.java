package com.recoverysystem.web.dto;

import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.TimeOfDayPreference;
import java.time.LocalDate;

public record WaitlistEntryResponse(
        Long id,
        Long patientId,
        Long currentAppointmentId,
        Long appointmentTypeId,
        Long preferredProviderId,
        LocalDate earliestAppointmentDate,
        LocalDate latestAppointmentDate,
        TimeOfDayPreference preferredTimeOfDay,
        String status) {

    public static WaitlistEntryResponse from(WaitlistEntry waitlistEntry) {
        return new WaitlistEntryResponse(
                waitlistEntry.getId(),
                waitlistEntry.getPatientId(),
                waitlistEntry.getCurrentAppointmentId(),
                waitlistEntry.getAppointmentTypeId(),
                waitlistEntry.getPreferredProviderId(),
                waitlistEntry.getEarliestAppointmentDate(),
                waitlistEntry.getLatestAppointmentDate(),
                waitlistEntry.getPreferredTimeOfDay(),
                waitlistEntry.getStatus().name());
    }
}

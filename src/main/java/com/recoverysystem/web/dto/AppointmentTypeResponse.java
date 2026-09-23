package com.recoverysystem.web.dto;

import com.recoverysystem.domain.entity.AppointmentType;

public record AppointmentTypeResponse(
        Long id, String name, int durationMinutes, Long specialtyId) {

    public static AppointmentTypeResponse from(AppointmentType appointmentType) {
        return new AppointmentTypeResponse(
                appointmentType.getId(),
                appointmentType.getName(),
                appointmentType.getDurationMinutes(),
                appointmentType.getSpecialtyId());
    }
}

package com.recoverysystem.exception;

import com.recoverysystem.domain.enums.AppointmentStatus;

public class AppointmentNotScheduledException extends RuntimeException {

    public AppointmentNotScheduledException(Long appointmentId, AppointmentStatus actualStatus) {
        super("Appointment %d must be SCHEDULED but was %s"
                .formatted(appointmentId, actualStatus));
    }
}

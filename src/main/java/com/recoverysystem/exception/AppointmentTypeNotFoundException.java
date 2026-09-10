package com.recoverysystem.exception;

public class AppointmentTypeNotFoundException extends RuntimeException {

    public AppointmentTypeNotFoundException(Long appointmentTypeId) {
        super("Appointment type not found: " + appointmentTypeId);
    }
}

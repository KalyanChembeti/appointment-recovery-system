package com.recoverysystem.exception;

public class AppointmentNotFoundException extends RuntimeException {

    public AppointmentNotFoundException(Long appointmentId) {
        super("Appointment not found: " + appointmentId);
    }
}

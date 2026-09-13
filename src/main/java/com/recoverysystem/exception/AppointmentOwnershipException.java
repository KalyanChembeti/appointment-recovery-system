package com.recoverysystem.exception;

public class AppointmentOwnershipException extends RuntimeException {

    public AppointmentOwnershipException(Long effectivePatientId, Long actualPatientId) {
        super("Appointment belongs to patient %d, not patient %d"
                .formatted(actualPatientId, effectivePatientId));
    }
}

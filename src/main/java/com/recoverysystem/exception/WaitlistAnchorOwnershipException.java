package com.recoverysystem.exception;

public class WaitlistAnchorOwnershipException extends RuntimeException {

    public WaitlistAnchorOwnershipException(
            Long appointmentId, Long expectedPatientId, Long actualPatientId) {
        super("Waitlist anchor appointment %d belongs to patient %d, not patient %d"
                .formatted(appointmentId, actualPatientId, expectedPatientId));
    }
}

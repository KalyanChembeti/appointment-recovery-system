package com.recoverysystem.exception;

public class WaitlistEntryOwnershipException extends RuntimeException {

    public WaitlistEntryOwnershipException(Long effectivePatientId, Long actualPatientId) {
        super("Waitlist entry belongs to patient %d, not patient %d"
                .formatted(actualPatientId, effectivePatientId));
    }
}

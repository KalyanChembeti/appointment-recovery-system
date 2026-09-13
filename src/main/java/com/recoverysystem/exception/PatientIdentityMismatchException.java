package com.recoverysystem.exception;

public class PatientIdentityMismatchException extends RuntimeException {

    public PatientIdentityMismatchException(
            Long requestedPatientId, Long authenticatedPatientId) {
        super("Requested patient %d does not match authenticated patient %d"
                .formatted(requestedPatientId, authenticatedPatientId));
    }
}

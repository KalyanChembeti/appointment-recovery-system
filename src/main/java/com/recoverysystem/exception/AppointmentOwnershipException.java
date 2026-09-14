package com.recoverysystem.exception;

public class AppointmentOwnershipException extends RuntimeException {

    public AppointmentOwnershipException(Long effectivePatientId, Long actualPatientId) {
        super("Appointment belongs to patient %d, not patient %d"
                .formatted(actualPatientId, effectivePatientId));
    }

    public static AppointmentOwnershipException forProvider(
            Long effectiveProviderId, Long actualProviderId) {
        return new AppointmentOwnershipException(
                "Appointment belongs to provider %d, not provider %d"
                        .formatted(actualProviderId, effectiveProviderId));
    }

    private AppointmentOwnershipException(String message) {
        super(message);
    }
}

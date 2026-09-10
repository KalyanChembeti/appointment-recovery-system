package com.recoverysystem.exception;

public class AppointmentTypeSpecialtyMismatchException extends RuntimeException {

    public AppointmentTypeSpecialtyMismatchException(
            Long appointmentTypeId,
            Long appointmentTypeSpecialtyId,
            Long providerId,
            Long providerSpecialtyId) {
        super("Appointment type %d has specialty %d, which does not match provider %d specialty %d"
                .formatted(
                        appointmentTypeId,
                        appointmentTypeSpecialtyId,
                        providerId,
                        providerSpecialtyId));
    }
}

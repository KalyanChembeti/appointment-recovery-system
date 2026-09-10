package com.recoverysystem.exception;

public class WaitlistAppointmentTypeMismatchException extends RuntimeException {

    public WaitlistAppointmentTypeMismatchException(
            Long appointmentId,
            Long requestedAppointmentTypeId,
            Long anchorAppointmentTypeId) {
        super("Requested appointment type %d does not match waitlist anchor appointment %d type %d"
                .formatted(
                        requestedAppointmentTypeId,
                        appointmentId,
                        anchorAppointmentTypeId));
    }
}

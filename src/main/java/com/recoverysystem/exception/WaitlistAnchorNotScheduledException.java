package com.recoverysystem.exception;

import com.recoverysystem.domain.enums.AppointmentStatus;

public class WaitlistAnchorNotScheduledException extends RuntimeException {

    public WaitlistAnchorNotScheduledException(Long appointmentId, AppointmentStatus status) {
        super("Waitlist anchor appointment %d must be SCHEDULED but was %s"
                .formatted(appointmentId, status));
    }
}

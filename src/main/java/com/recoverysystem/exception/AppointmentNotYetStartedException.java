package com.recoverysystem.exception;

import java.time.Instant;

public class AppointmentNotYetStartedException extends RuntimeException {

    public AppointmentNotYetStartedException(Long appointmentId, Instant startAt) {
        super("Appointment %d has not started yet; scheduled start is %s"
                .formatted(appointmentId, startAt));
    }
}

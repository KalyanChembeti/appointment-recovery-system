package com.recoverysystem.exception;

import java.util.List;

public class SiblingAlreadyFulfilledException extends RuntimeException {

    public SiblingAlreadyFulfilledException(
            Long appointmentId, List<Long> fulfilledEntryIds) {
        super("Appointment %d already has fulfilled waitlist entries %s"
                .formatted(appointmentId, fulfilledEntryIds));
    }
}

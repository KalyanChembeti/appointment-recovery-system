package com.recoverysystem.exception;

import com.recoverysystem.domain.enums.CancellationReason;

public class InvalidCancellationReasonException extends RuntimeException {

    public InvalidCancellationReasonException(
            Long appointmentId, CancellationReason cancellationReason) {
        super("Appointment %d cannot be normally cancelled with reason %s"
                .formatted(appointmentId, cancellationReason));
    }
}

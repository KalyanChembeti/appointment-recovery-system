package com.recoverysystem.exception;

public class WaitlistEntryAnchorMismatchException extends RuntimeException {

    public WaitlistEntryAnchorMismatchException(
            Long waitlistEntryId, Long routedAppointmentId, Long lockedAppointmentId) {
        super("Waitlist entry %d changed anchor appointment from %d to %d while being modified"
                .formatted(waitlistEntryId, routedAppointmentId, lockedAppointmentId));
    }
}

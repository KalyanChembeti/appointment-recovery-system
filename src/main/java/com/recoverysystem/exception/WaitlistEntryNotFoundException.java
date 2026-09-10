package com.recoverysystem.exception;

public class WaitlistEntryNotFoundException extends RuntimeException {

    public WaitlistEntryNotFoundException(Long waitlistEntryId) {
        super("Waitlist entry not found: " + waitlistEntryId);
    }
}

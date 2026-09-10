package com.recoverysystem.exception;

import com.recoverysystem.domain.enums.WaitlistEntryStatus;

public class WaitlistEntryNotActiveException extends RuntimeException {

    public WaitlistEntryNotActiveException(
            Long waitlistEntryId, WaitlistEntryStatus actualStatus) {
        super("Waitlist entry %d must be ACTIVE but was %s"
                .formatted(waitlistEntryId, actualStatus));
    }
}

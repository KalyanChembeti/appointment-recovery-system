package com.recoverysystem.exception;

import java.time.LocalDate;

public class InvalidWaitlistDateRangeException extends RuntimeException {

    public InvalidWaitlistDateRangeException(LocalDate earliestDate, LocalDate latestDate) {
        super("Waitlist earliest date %s must not be after latest date %s"
                .formatted(earliestDate, latestDate));
    }
}

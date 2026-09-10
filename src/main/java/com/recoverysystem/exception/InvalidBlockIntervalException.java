package com.recoverysystem.exception;

import java.time.Instant;

public class InvalidBlockIntervalException extends RuntimeException {

    public InvalidBlockIntervalException(Instant startAt, Instant endAt) {
        super("Provider block start must be before end: startAt=%s, endAt=%s"
                .formatted(startAt, endAt));
    }
}

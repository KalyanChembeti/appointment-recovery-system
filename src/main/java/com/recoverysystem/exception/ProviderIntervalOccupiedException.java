package com.recoverysystem.exception;

import java.time.Instant;

public class ProviderIntervalOccupiedException extends RuntimeException {

    public ProviderIntervalOccupiedException(
            Long providerId, Instant startAt, Instant endAt) {
        super("Provider %d already has a scheduled appointment from %s to %s"
                .formatted(providerId, startAt, endAt));
    }
}

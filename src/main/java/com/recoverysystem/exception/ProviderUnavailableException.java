package com.recoverysystem.exception;

import java.time.Instant;

public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(Long providerId, Instant startAt, Instant endAt) {
        super("Provider %d is unavailable from %s to %s".formatted(providerId, startAt, endAt));
    }
}

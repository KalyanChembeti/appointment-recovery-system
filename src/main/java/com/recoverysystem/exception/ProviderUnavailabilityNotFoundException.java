package com.recoverysystem.exception;

public class ProviderUnavailabilityNotFoundException extends RuntimeException {

    public ProviderUnavailabilityNotFoundException(Long providerUnavailabilityId) {
        super("Provider unavailability not found: " + providerUnavailabilityId);
    }
}

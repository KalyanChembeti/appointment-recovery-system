package com.recoverysystem.exception;

public class ProviderNotFoundException extends RuntimeException {

    public ProviderNotFoundException(Long providerId) {
        super("Provider not found: " + providerId);
    }
}

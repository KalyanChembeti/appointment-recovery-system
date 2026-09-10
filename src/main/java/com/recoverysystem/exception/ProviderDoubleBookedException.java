package com.recoverysystem.exception;

public class ProviderDoubleBookedException extends RuntimeException {

    public ProviderDoubleBookedException(Throwable cause) {
        super("The provider already has a scheduled appointment during the requested interval", cause);
    }
}

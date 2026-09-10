package com.recoverysystem.exception;

public class PatientDoubleBookedException extends RuntimeException {

    public PatientDoubleBookedException(Throwable cause) {
        super("The patient already has a scheduled appointment during the requested interval", cause);
    }
}

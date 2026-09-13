package com.recoverysystem.exception;

import com.recoverysystem.domain.enums.UserRole;

public class MissingPatientIdException extends RuntimeException {

    public MissingPatientIdException(UserRole callerRole) {
        super("Patient ID is required when acting as " + callerRole);
    }
}

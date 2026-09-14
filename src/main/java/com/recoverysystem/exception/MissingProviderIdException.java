package com.recoverysystem.exception;

import com.recoverysystem.domain.enums.UserRole;

public class MissingProviderIdException extends RuntimeException {

    public MissingProviderIdException(UserRole callerRole) {
        super("Provider ID is required when acting as " + callerRole);
    }
}

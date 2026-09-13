package com.recoverysystem.exception;

import com.recoverysystem.domain.enums.UserRole;

public class ProviderActionNotPermittedException extends RuntimeException {

    public ProviderActionNotPermittedException(UserRole callerRole) {
        super("Role " + callerRole + " is not permitted to perform patient actions");
    }
}

package com.recoverysystem.exception;

import com.recoverysystem.domain.enums.UserRole;

public class InvalidProviderUserRoleException extends RuntimeException {

    public InvalidProviderUserRoleException(Long userId, UserRole actualRole) {
        super("User %d has role %s; PROVIDER role is required".formatted(userId, actualRole));
    }
}

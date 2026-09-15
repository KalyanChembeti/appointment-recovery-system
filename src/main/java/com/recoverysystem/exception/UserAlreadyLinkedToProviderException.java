package com.recoverysystem.exception;

public class UserAlreadyLinkedToProviderException extends RuntimeException {

    public UserAlreadyLinkedToProviderException(Long userId) {
        super("User %d is already linked to a provider".formatted(userId));
    }

    public UserAlreadyLinkedToProviderException(Long userId, Throwable cause) {
        super("User %d is already linked to a provider".formatted(userId), cause);
    }
}

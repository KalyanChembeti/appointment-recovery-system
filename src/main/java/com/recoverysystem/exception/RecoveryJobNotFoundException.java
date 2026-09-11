package com.recoverysystem.exception;

public class RecoveryJobNotFoundException extends RuntimeException {

    public RecoveryJobNotFoundException(Long recoveryJobId) {
        super("Recovery job not found: " + recoveryJobId);
    }
}

package com.recoverysystem.exception;

import com.recoverysystem.domain.enums.RecoveryJobStatus;

public class RecoveryJobNotOpenException extends RuntimeException {

    public RecoveryJobNotOpenException(Long recoveryJobId, RecoveryJobStatus actualStatus) {
        super("Recovery job %d must be OPEN but was %s"
                .formatted(recoveryJobId, actualStatus));
    }
}

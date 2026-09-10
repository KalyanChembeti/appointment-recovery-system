package com.recoverysystem.exception;

import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;

public class ProviderBlockNotPendingException extends RuntimeException {

    public ProviderBlockNotPendingException(
            Long providerUnavailabilityId, ProviderUnavailabilityStatus actualStatus) {
        super("Provider unavailability %d must be PENDING but was %s"
                .formatted(providerUnavailabilityId, actualStatus));
    }
}

package com.recoverysystem.exception;

import java.util.List;

public class ProviderBlockConflictsUnresolvedException extends RuntimeException {

    public ProviderBlockConflictsUnresolvedException(
            Long providerUnavailabilityId,
            Long providerId,
            List<Long> conflictingAppointmentIds) {
        super("Provider unavailability %d for provider %d still conflicts with appointments %s"
                .formatted(
                        providerUnavailabilityId,
                        providerId,
                        conflictingAppointmentIds));
    }
}

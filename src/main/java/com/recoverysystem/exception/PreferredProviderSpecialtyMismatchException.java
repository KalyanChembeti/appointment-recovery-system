package com.recoverysystem.exception;

public class PreferredProviderSpecialtyMismatchException extends RuntimeException {

    public PreferredProviderSpecialtyMismatchException(
            Long preferredProviderId,
            Long providerSpecialtyId,
            Long appointmentTypeId,
            Long appointmentTypeSpecialtyId) {
        super("Preferred provider %d has specialty %d, which does not match appointment type %d specialty %d"
                .formatted(
                        preferredProviderId,
                        providerSpecialtyId,
                        appointmentTypeId,
                        appointmentTypeSpecialtyId));
    }
}

package com.recoverysystem.exception;

public class ProviderBlockOwnershipException extends RuntimeException {

    public ProviderBlockOwnershipException(
            Long providerUnavailabilityId,
            Long authenticatedProviderId,
            Long actualProviderId) {
        super("Provider unavailability %d belongs to provider %d, not provider %d"
                .formatted(providerUnavailabilityId, actualProviderId, authenticatedProviderId));
    }

    public static ProviderBlockOwnershipException forRequestedProvider(
            Long authenticatedProviderId, Long requestedProviderId) {
        return new ProviderBlockOwnershipException(
                "Requested provider %d does not match authenticated provider %d"
                        .formatted(requestedProviderId, authenticatedProviderId));
    }

    private ProviderBlockOwnershipException(String message) {
        super(message);
    }
}

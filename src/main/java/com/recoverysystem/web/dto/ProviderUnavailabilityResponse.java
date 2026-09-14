package com.recoverysystem.web.dto;

import com.recoverysystem.domain.entity.ProviderUnavailability;
import java.time.Instant;
import java.util.List;

public record ProviderUnavailabilityResponse(
        Long id,
        Long providerId,
        Instant startAt,
        Instant endAt,
        String status,
        String reason,
        List<Long> conflictingAppointmentIds) {

    public static ProviderUnavailabilityResponse from(
            ProviderUnavailability providerUnavailability,
            List<Long> conflictingAppointmentIds) {
        return new ProviderUnavailabilityResponse(
                providerUnavailability.getId(),
                providerUnavailability.getProviderId(),
                providerUnavailability.getStartAt(),
                providerUnavailability.getEndAt(),
                providerUnavailability.getStatus().name(),
                providerUnavailability.getReason(),
                List.copyOf(conflictingAppointmentIds));
    }

    public static ProviderUnavailabilityResponse from(
            ProviderUnavailability providerUnavailability) {
        return from(providerUnavailability, List.of());
    }
}

package com.recoverysystem.web.dto;

import com.recoverysystem.domain.enums.SlotOfferStatus;
import java.time.Instant;

public record SlotOfferResponse(
        Long id,
        Long recoveryJobId,
        Long waitlistEntryId,
        String status,
        Instant expiresAt,
        Instant acceptedAt,
        Long providerId,
        Long appointmentTypeId,
        Instant startAt,
        Instant endAt) {

    public SlotOfferResponse(
            Long id,
            Long recoveryJobId,
            Long waitlistEntryId,
            SlotOfferStatus status,
            Instant expiresAt,
            Instant acceptedAt,
            Long providerId,
            Long appointmentTypeId,
            Instant startAt,
            Instant endAt) {
        this(
                id,
                recoveryJobId,
                waitlistEntryId,
                status.name(),
                expiresAt,
                acceptedAt,
                providerId,
                appointmentTypeId,
                startAt,
                endAt);
    }
}

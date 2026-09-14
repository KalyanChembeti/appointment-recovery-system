package com.recoverysystem.web.dto;

import com.recoverysystem.domain.entity.SlotOffer;
import java.time.Instant;

public record SlotOfferResponse(
        Long id,
        Long recoveryJobId,
        Long waitlistEntryId,
        String status,
        Instant expiresAt,
        Instant acceptedAt) {

    public static SlotOfferResponse from(SlotOffer slotOffer) {
        return new SlotOfferResponse(
                slotOffer.getId(),
                slotOffer.getRecoveryJobId(),
                slotOffer.getWaitlistEntryId(),
                slotOffer.getStatus().name(),
                slotOffer.getExpiresAt(),
                slotOffer.getAcceptedAt());
    }
}

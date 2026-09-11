package com.recoverysystem.exception;

public class OfferAcceptanceOwnershipException extends RuntimeException {

    public OfferAcceptanceOwnershipException(
            Long slotOfferId, Long requestedPatientId, Long actualPatientId) {
        super("Slot offer %d belongs to patient %d, not patient %d"
                .formatted(slotOfferId, actualPatientId, requestedPatientId));
    }
}

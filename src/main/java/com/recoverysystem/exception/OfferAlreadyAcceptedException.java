package com.recoverysystem.exception;

public class OfferAlreadyAcceptedException extends RuntimeException {

    public OfferAlreadyAcceptedException(Long slotOfferId) {
        super("Slot offer %d has already been accepted".formatted(slotOfferId));
    }
}

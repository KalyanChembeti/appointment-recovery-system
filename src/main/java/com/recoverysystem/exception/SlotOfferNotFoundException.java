package com.recoverysystem.exception;

public class SlotOfferNotFoundException extends RuntimeException {

    public SlotOfferNotFoundException(Long slotOfferId) {
        super("Slot offer not found: " + slotOfferId);
    }
}

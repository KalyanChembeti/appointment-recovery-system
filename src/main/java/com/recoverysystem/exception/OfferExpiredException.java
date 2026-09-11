package com.recoverysystem.exception;

public class OfferExpiredException extends RuntimeException {

    public OfferExpiredException(Long slotOfferId) {
        super("Slot offer %d has expired".formatted(slotOfferId));
    }
}

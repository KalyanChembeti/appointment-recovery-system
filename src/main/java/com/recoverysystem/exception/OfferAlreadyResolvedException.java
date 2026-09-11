package com.recoverysystem.exception;

import com.recoverysystem.domain.enums.SlotOfferStatus;

public class OfferAlreadyResolvedException extends RuntimeException {

    public OfferAlreadyResolvedException(Long slotOfferId, SlotOfferStatus actualStatus) {
        super("Slot offer %d has already been resolved with status %s"
                .formatted(slotOfferId, actualStatus));
    }
}

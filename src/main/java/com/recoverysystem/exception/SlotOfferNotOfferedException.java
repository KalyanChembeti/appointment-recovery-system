package com.recoverysystem.exception;

import com.recoverysystem.domain.enums.SlotOfferStatus;

public class SlotOfferNotOfferedException extends RuntimeException {

    public SlotOfferNotOfferedException(Long slotOfferId, SlotOfferStatus actualStatus) {
        super("Slot offer %d must be OFFERED but was %s"
                .formatted(slotOfferId, actualStatus));
    }
}

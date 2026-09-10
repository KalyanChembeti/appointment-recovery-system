package com.recoverysystem.service;

import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.repository.SlotOfferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SlotOfferExpiryOfferProcessor {

    private final SlotOfferRepository slotOfferRepository;
    private final SlotOfferAggressiveExpiryTransition slotOfferAggressiveExpiryTransition;

    public SlotOfferExpiryOfferProcessor(
            SlotOfferRepository slotOfferRepository,
            SlotOfferAggressiveExpiryTransition slotOfferAggressiveExpiryTransition) {
        this.slotOfferRepository = slotOfferRepository;
        this.slotOfferAggressiveExpiryTransition = slotOfferAggressiveExpiryTransition;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean expireOfferIfEligible(Long slotOfferId) {
        SlotOffer slotOffer = slotOfferRepository.findByIdForUpdate(slotOfferId).orElse(null);
        if (slotOffer == null) {
            return false;
        }
        return slotOfferAggressiveExpiryTransition.expireIfStaleOffered(slotOffer);
    }
}

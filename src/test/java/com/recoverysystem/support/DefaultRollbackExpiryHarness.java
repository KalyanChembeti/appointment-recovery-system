package com.recoverysystem.support;

import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.exception.SlotOfferNotFoundException;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.service.AcceptedOfferTerminalStateResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DefaultRollbackExpiryHarness {

    private final SlotOfferRepository slotOfferRepository;
    private final AcceptedOfferTerminalStateResolver acceptedOfferTerminalStateResolver;

    public DefaultRollbackExpiryHarness(
            SlotOfferRepository slotOfferRepository,
            AcceptedOfferTerminalStateResolver acceptedOfferTerminalStateResolver) {
        this.slotOfferRepository = slotOfferRepository;
        this.acceptedOfferTerminalStateResolver = acceptedOfferTerminalStateResolver;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void lockAndResolve(Long slotOfferId) {
        SlotOffer slotOffer = slotOfferRepository.findByIdForUpdate(slotOfferId)
                .orElseThrow(() -> new SlotOfferNotFoundException(slotOfferId));
        acceptedOfferTerminalStateResolver.resolve(slotOffer);
    }
}

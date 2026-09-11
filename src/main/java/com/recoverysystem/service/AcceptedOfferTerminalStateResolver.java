package com.recoverysystem.service;

import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.exception.OfferAlreadyAcceptedException;
import com.recoverysystem.exception.OfferAlreadyResolvedException;
import com.recoverysystem.exception.OfferExpiredException;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Resolves the terminal state of a SlotOffer already locked by its caller.
 *
 * <p>Governing invariant for the eventual Workflow 4 transaction: its orchestrating method
 * must use {@code @Transactional(noRollbackFor = OfferExpiredException.class)} so an aggressive
 * expiry can commit when this resolver throws. The orchestrator must invoke this resolver before
 * beginning any Appointment, WaitlistEntry, RecoveryJob, or other acceptance mutation; otherwise,
 * {@code noRollbackFor} could commit partial acceptance work. This component never manages a
 * transaction or acquires a lock itself.
 */
@Service
public class AcceptedOfferTerminalStateResolver {

    private final SlotOfferAggressiveExpiryTransition slotOfferAggressiveExpiryTransition;

    public AcceptedOfferTerminalStateResolver(
            SlotOfferAggressiveExpiryTransition slotOfferAggressiveExpiryTransition) {
        this.slotOfferAggressiveExpiryTransition = slotOfferAggressiveExpiryTransition;
    }

    public void resolve(SlotOffer lockedAcceptedOffer) {
        switch (lockedAcceptedOffer.getStatus()) {
            case ACCEPTED -> throw new OfferAlreadyAcceptedException(lockedAcceptedOffer.getId());
            case DECLINED, CANCELLED -> throw new OfferAlreadyResolvedException(
                    lockedAcceptedOffer.getId(), lockedAcceptedOffer.getStatus());
            case EXPIRED -> throw new OfferExpiredException(lockedAcceptedOffer.getId());
            case OFFERED -> {
                if (lockedAcceptedOffer.getExpiresAt().isAfter(Instant.now())) {
                    return;
                }
                slotOfferAggressiveExpiryTransition.expireIfStaleOffered(lockedAcceptedOffer);
                throw new OfferExpiredException(lockedAcceptedOffer.getId());
            }
        }
    }
}

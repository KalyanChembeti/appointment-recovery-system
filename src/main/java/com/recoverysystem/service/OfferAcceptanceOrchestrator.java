package com.recoverysystem.service;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.exception.PatientDoubleBookedException;
import com.recoverysystem.exception.SlotOfferNotFoundException;
import com.recoverysystem.repository.SlotOfferRepository;
import org.springframework.stereotype.Service;

@Service
public class OfferAcceptanceOrchestrator {

    private final SlotOfferRepository slotOfferRepository;
    private final OfferAcceptanceService offerAcceptanceService;
    private final OfferAcceptancePatientConflictCleanup patientConflictCleanup;

    public OfferAcceptanceOrchestrator(
            SlotOfferRepository slotOfferRepository,
            OfferAcceptanceService offerAcceptanceService,
            OfferAcceptancePatientConflictCleanup patientConflictCleanup) {
        this.slotOfferRepository = slotOfferRepository;
        this.offerAcceptanceService = offerAcceptanceService;
        this.patientConflictCleanup = patientConflictCleanup;
    }

    public Appointment acceptOffer(Long slotOfferId, Long patientId, Long actorUserId) {
        SlotOffer slotOffer = slotOfferRepository.findById(slotOfferId)
                .orElseThrow(() -> new SlotOfferNotFoundException(slotOfferId));
        Long recoveryJobId = slotOffer.getRecoveryJobId();

        try {
            return offerAcceptanceService.acceptOfferTransaction(
                    slotOfferId, patientId, actorUserId);
        } catch (PatientDoubleBookedException exception) {
            ActorType actorType = actorUserId == null ? ActorType.SYSTEM : ActorType.USER;
            patientConflictCleanup.cleanupAfterPatientConflict(
                    recoveryJobId, slotOfferId, actorType, actorUserId);
            throw exception;
        }
    }
}

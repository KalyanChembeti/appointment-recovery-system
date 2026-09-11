package com.recoverysystem.service;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfferAcceptancePatientConflictCleanup {

    private static final String PATIENT_CONFLICT_REASON = "PATIENT_SCHEDULE_CONFLICT";

    private final RecoveryJobRepository recoveryJobRepository;
    private final SlotOfferRepository slotOfferRepository;
    private final AuditLogRepository auditLogRepository;

    public OfferAcceptancePatientConflictCleanup(
            RecoveryJobRepository recoveryJobRepository,
            SlotOfferRepository slotOfferRepository,
            AuditLogRepository auditLogRepository) {
        this.recoveryJobRepository = recoveryJobRepository;
        this.slotOfferRepository = slotOfferRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRES_NEW)
    public void cleanupAfterPatientConflict(
            Long recoveryJobId,
            Long slotOfferId,
            ActorType actorType,
            Long actorUserId) {
        RecoveryJob recoveryJob = recoveryJobRepository.findByIdForUpdate(recoveryJobId)
                .orElse(null);
        if (recoveryJob == null) {
            return;
        }

        SlotOffer slotOffer = slotOfferRepository.findByIdForUpdate(slotOfferId)
                .orElse(null);
        if (slotOffer == null) {
            return;
        }

        // The recovery job is locked only to preserve its current state during cleanup.
        // Its status and other values must stay unchanged.
        if (slotOffer.getStatus() != SlotOfferStatus.OFFERED) {
            return;
        }

        slotOffer.setStatus(SlotOfferStatus.CANCELLED);

        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType("SlotOffer");
        auditLog.setEntityId(slotOfferId);
        auditLog.setAction("CANCEL");
        auditLog.setActorType(actorType);
        auditLog.setActorUserId(actorUserId);
        auditLog.setReason(PATIENT_CONFLICT_REASON);
        auditLogRepository.save(auditLog);
    }
}

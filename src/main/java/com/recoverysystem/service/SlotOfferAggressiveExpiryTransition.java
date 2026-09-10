package com.recoverysystem.service;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.repository.AuditLogRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class SlotOfferAggressiveExpiryTransition {

    private final AuditLogRepository auditLogRepository;

    public SlotOfferAggressiveExpiryTransition(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public boolean expireIfStaleOffered(SlotOffer lockedOffer) {
        if (lockedOffer.getStatus() != SlotOfferStatus.OFFERED
                || lockedOffer.getExpiresAt().isAfter(Instant.now())) {
            return false;
        }

        lockedOffer.setStatus(SlotOfferStatus.EXPIRED);

        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType("SlotOffer");
        auditLog.setEntityId(lockedOffer.getId());
        auditLog.setAction("EXPIRE");
        auditLog.setActorType(ActorType.SYSTEM);
        auditLog.setActorUserId(null);
        auditLogRepository.save(auditLog);

        return true;
    }
}

package com.recoverysystem.service;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.exception.SlotOfferNotFoundException;
import com.recoverysystem.exception.SlotOfferNotOfferedException;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SlotOfferDeclineService {

    private final SlotOfferRepository slotOfferRepository;
    private final AuditLogRepository auditLogRepository;

    public SlotOfferDeclineService(
            SlotOfferRepository slotOfferRepository,
            AuditLogRepository auditLogRepository) {
        this.slotOfferRepository = slotOfferRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SlotOffer declineOffer(Long slotOfferId, Long patientId) {
        SlotOffer slotOffer = slotOfferRepository.findByIdForUpdate(slotOfferId)
                .orElseThrow(() -> new SlotOfferNotFoundException(slotOfferId));

        if (slotOffer.getStatus() != SlotOfferStatus.OFFERED) {
            throw new SlotOfferNotOfferedException(slotOfferId, slotOffer.getStatus());
        }

        slotOffer.setStatus(SlotOfferStatus.DECLINED);

        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType("SlotOffer");
        auditLog.setEntityId(slotOfferId);
        auditLog.setAction("DECLINE");
        auditLog.setActorType(ActorType.USER);
        auditLog.setActorUserId(patientId);
        auditLogRepository.save(auditLog);

        return slotOffer;
    }
}

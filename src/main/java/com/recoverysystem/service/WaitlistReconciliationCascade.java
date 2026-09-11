package com.recoverysystem.service;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WaitlistReconciliationCascade {

    private final WaitlistEntryRepository waitlistEntryRepository;
    private final SlotOfferRepository slotOfferRepository;
    private final AuditLogRepository auditLogRepository;

    public WaitlistReconciliationCascade(
            WaitlistEntryRepository waitlistEntryRepository,
            SlotOfferRepository slotOfferRepository,
            AuditLogRepository auditLogRepository) {
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.slotOfferRepository = slotOfferRepository;
        this.auditLogRepository = auditLogRepository;
    }

    public void reconcileAnchoredWaitlistEntries(
            Long appointmentId,
            String slotOfferCancellationReason,
            ActorType actorType,
            Long actorUserId) {
        List<WaitlistEntry> activeWaitlistEntries =
                waitlistEntryRepository.findByCurrentAppointmentIdAndStatusForUpdate(
                        appointmentId, WaitlistEntryStatus.ACTIVE);
        List<Long> waitlistEntryIds =
                activeWaitlistEntries.stream().map(WaitlistEntry::getId).toList();
        List<SlotOffer> offeredSlotOffers = waitlistEntryIds.isEmpty()
                ? List.of()
                : slotOfferRepository.findByWaitlistEntryIdsAndStatusForUpdate(
                        waitlistEntryIds, SlotOfferStatus.OFFERED);

        activeWaitlistEntries.forEach(waitlistEntry -> {
            waitlistEntry.setStatus(WaitlistEntryStatus.REMOVED);
            auditLogRepository.save(createAuditLog(
                    "WaitlistEntry",
                    waitlistEntry.getId(),
                    "REMOVE",
                    actorType,
                    actorUserId,
                    null));
        });

        offeredSlotOffers.forEach(slotOffer -> {
            slotOffer.setStatus(SlotOfferStatus.CANCELLED);
            auditLogRepository.save(createAuditLog(
                    "SlotOffer",
                    slotOffer.getId(),
                    "CANCEL",
                    actorType,
                    actorUserId,
                    slotOfferCancellationReason));
        });
    }

    private AuditLog createAuditLog(
            String entityType,
            Long entityId,
            String action,
            ActorType actorType,
            Long actorUserId,
            String reason) {
        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType(entityType);
        auditLog.setEntityId(entityId);
        auditLog.setAction(action);
        auditLog.setActorType(actorType);
        auditLog.setActorUserId(actorUserId);
        auditLog.setReason(reason);
        return auditLog;
    }
}

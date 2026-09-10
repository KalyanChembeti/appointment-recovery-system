package com.recoverysystem.service;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.exception.WaitlistEntryNotActiveException;
import com.recoverysystem.exception.WaitlistEntryNotFoundException;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WaitlistEntryRemovalService {

    private static final String ENTRY_REMOVED_REASON = "ENTRY_REMOVED";

    private final WaitlistEntryRepository waitlistEntryRepository;
    private final SlotOfferRepository slotOfferRepository;
    private final AuditLogRepository auditLogRepository;

    public WaitlistEntryRemovalService(
            WaitlistEntryRepository waitlistEntryRepository,
            SlotOfferRepository slotOfferRepository,
            AuditLogRepository auditLogRepository) {
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.slotOfferRepository = slotOfferRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WaitlistEntry removeWaitlistEntry(Long waitlistEntryId, Long actorUserId) {
        WaitlistEntry waitlistEntry = waitlistEntryRepository.findByIdForUpdate(waitlistEntryId)
                .orElseThrow(() -> new WaitlistEntryNotFoundException(waitlistEntryId));

        if (waitlistEntry.getStatus() != WaitlistEntryStatus.ACTIVE) {
            throw new WaitlistEntryNotActiveException(
                    waitlistEntryId, waitlistEntry.getStatus());
        }

        List<SlotOffer> offeredSlotOffers =
                slotOfferRepository.findByWaitlistEntryIdAndStatusForUpdate(
                        waitlistEntryId, SlotOfferStatus.OFFERED);

        waitlistEntry.setStatus(WaitlistEntryStatus.REMOVED);
        offeredSlotOffers.forEach(slotOffer -> slotOffer.setStatus(SlotOfferStatus.CANCELLED));

        ActorType actorType = actorUserId == null ? ActorType.SYSTEM : ActorType.USER;
        auditLogRepository.save(createAuditLog(
                "WaitlistEntry",
                waitlistEntryId,
                "REMOVE",
                actorType,
                actorUserId,
                null));
        offeredSlotOffers.forEach(slotOffer -> auditLogRepository.save(createAuditLog(
                "SlotOffer",
                slotOffer.getId(),
                "CANCEL",
                actorType,
                actorUserId,
                ENTRY_REMOVED_REASON)));

        return waitlistEntry;
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

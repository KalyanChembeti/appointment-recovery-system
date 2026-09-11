package com.recoverysystem.service;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SlotOfferCleanupDiscovery {

    private final SlotOfferRepository slotOfferRepository;
    private final AuditLogRepository auditLogRepository;

    public SlotOfferCleanupDiscovery(
            SlotOfferRepository slotOfferRepository,
            AuditLogRepository auditLogRepository) {
        this.slotOfferRepository = slotOfferRepository;
        this.auditLogRepository = auditLogRepository;
    }

    public List<Long> discoverCleanupOfferIds(
            Long acceptedOfferId,
            Long acceptedEntryId,
            List<Long> siblingEntryIds,
            Long patientId,
            Long oldAppointmentId,
            Instant newIntervalStart,
            Instant newIntervalEnd) {
        List<Long> entryIds = new ArrayList<>();
        entryIds.add(acceptedEntryId);
        entryIds.addAll(siblingEntryIds);
        entryIds = entryIds.stream().distinct().toList();

        List<Long> offerIds = new ArrayList<>(
                slotOfferRepository.findOfferedIdsByWaitlistEntryIdsExcludingOffer(
                        entryIds, acceptedOfferId));
        offerIds.addAll(slotOfferRepository.findOfferedIdsForOtherActivePatientEntriesOverlapping(
                patientId,
                oldAppointmentId,
                newIntervalStart,
                newIntervalEnd));

        return offerIds.stream().distinct().sorted().toList();
    }

    public void cleanupIfStillOffered(
            SlotOffer lockedCleanupOffer,
            String reason,
            ActorType actorType,
            Long actorUserId) {
        if (lockedCleanupOffer.getStatus() != SlotOfferStatus.OFFERED) {
            return;
        }

        lockedCleanupOffer.setStatus(SlotOfferStatus.CANCELLED);

        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType("SlotOffer");
        auditLog.setEntityId(lockedCleanupOffer.getId());
        auditLog.setAction("CANCEL");
        auditLog.setActorType(actorType);
        auditLog.setActorUserId(actorUserId);
        auditLog.setReason(reason);
        auditLogRepository.save(auditLog);
    }
}

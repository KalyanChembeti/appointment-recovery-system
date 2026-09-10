package com.recoverysystem.service;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RecoveryJobSuppressionCascade {

    private static final String JOB_SUPPRESSION_REASON = "PROVIDER_BLOCK_ACTIVATED";
    private static final String OFFER_CANCELLATION_REASON = "RECOVERY_JOB_SUPPRESSED";

    private final RecoveryJobRepository recoveryJobRepository;
    private final SlotOfferRepository slotOfferRepository;
    private final AuditLogRepository auditLogRepository;

    public RecoveryJobSuppressionCascade(
            RecoveryJobRepository recoveryJobRepository,
            SlotOfferRepository slotOfferRepository,
            AuditLogRepository auditLogRepository) {
        this.recoveryJobRepository = recoveryJobRepository;
        this.slotOfferRepository = slotOfferRepository;
        this.auditLogRepository = auditLogRepository;
    }

    public void suppressAffectedRecoveryJobs(
            Long providerId,
            Instant blockStart,
            Instant blockEnd,
            ActorType actorType,
            Long actorUserId) {
        List<RecoveryJob> recoveryJobs =
                recoveryJobRepository.findOpenOverlappingByProviderForUpdate(
                        providerId, blockStart, blockEnd);
        List<Long> recoveryJobIds = recoveryJobs.stream().map(RecoveryJob::getId).toList();
        List<SlotOffer> slotOffers = recoveryJobIds.isEmpty()
                ? List.of()
                : slotOfferRepository.findOfferedByRecoveryJobIdsForUpdate(recoveryJobIds);

        recoveryJobs.forEach(recoveryJob -> {
            recoveryJob.setStatus(RecoveryJobStatus.SUPPRESSED);
            recoveryJob.setSuppressionReason(JOB_SUPPRESSION_REASON);
        });
        slotOffers.forEach(slotOffer -> slotOffer.setStatus(SlotOfferStatus.CANCELLED));

        recoveryJobs.forEach(recoveryJob -> auditLogRepository.save(createAuditLog(
                "RecoveryJob",
                recoveryJob.getId(),
                "SUPPRESS",
                actorType,
                actorUserId,
                null)));
        slotOffers.forEach(slotOffer -> auditLogRepository.save(createAuditLog(
                "SlotOffer",
                slotOffer.getId(),
                "CANCEL",
                actorType,
                actorUserId,
                OFFER_CANCELLATION_REASON)));
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

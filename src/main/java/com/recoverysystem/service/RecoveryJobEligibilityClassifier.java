package com.recoverysystem.service;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.entity.SchedulingPolicy;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import com.recoverysystem.repository.SchedulingPolicyRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

@Service
public class RecoveryJobEligibilityClassifier {

    private static final String INTERVAL_OCCUPIED_REASON = "INTERVAL_ALREADY_OCCUPIED";
    private static final String PROVIDER_BLOCK_REASON = "PROVIDER_BLOCK_ACTIVATED";
    private static final String LEAD_TIME_REASON = "LEAD_TIME_CLOSED";

    private final AppointmentRepository appointmentRepository;
    private final ProviderUnavailabilityRepository providerUnavailabilityRepository;
    private final SchedulingPolicyRepository schedulingPolicyRepository;
    private final AuditLogRepository auditLogRepository;

    public RecoveryJobEligibilityClassifier(
            AppointmentRepository appointmentRepository,
            ProviderUnavailabilityRepository providerUnavailabilityRepository,
            SchedulingPolicyRepository schedulingPolicyRepository,
            AuditLogRepository auditLogRepository) {
        this.appointmentRepository = appointmentRepository;
        this.providerUnavailabilityRepository = providerUnavailabilityRepository;
        this.schedulingPolicyRepository = schedulingPolicyRepository;
        this.auditLogRepository = auditLogRepository;
    }

    public boolean classifyAndHandle(
            RecoveryJob lockedRecoveryJob,
            Long releasedProviderId,
            Instant releasedStartAt,
            Instant releasedEndAt,
            ActorType actorType,
            Long actorUserId) {
        if (!appointmentRepository.findScheduledOverlappingIds(
                        releasedProviderId, releasedStartAt, releasedEndAt)
                .isEmpty()) {
            lockedRecoveryJob.setStatus(RecoveryJobStatus.FILLED);
            lockedRecoveryJob.setFilledAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
            auditLogRepository.save(createAuditLog(
                    lockedRecoveryJob.getId(),
                    "FILLED",
                    actorType,
                    actorUserId,
                    INTERVAL_OCCUPIED_REASON));
            return false;
        }

        if (providerUnavailabilityRepository.existsOverlappingActiveBlock(
                releasedProviderId, releasedStartAt, releasedEndAt)) {
            lockedRecoveryJob.setStatus(RecoveryJobStatus.SUPPRESSED);
            lockedRecoveryJob.setSuppressionReason(PROVIDER_BLOCK_REASON);
            auditLogRepository.save(createAuditLog(
                    lockedRecoveryJob.getId(), "SUPPRESS", actorType, actorUserId, null));
            return false;
        }

        if (providerUnavailabilityRepository.existsOverlappingPendingBlock(
                releasedProviderId, releasedStartAt, releasedEndAt)) {
            return false;
        }

        SchedulingPolicy policy = schedulingPolicyRepository.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "SchedulingPolicy singleton row is missing -- V2 migration should have inserted "
                                + "exactly one row and V3's unique index should prevent more than one from ever "
                                + "existing"));
        Instant leadTimeThreshold = Instant.now().plus(
                policy.getMinimumRecoveryLeadMinutes(), ChronoUnit.MINUTES);
        if (releasedStartAt.isBefore(leadTimeThreshold)) {
            lockedRecoveryJob.setStatus(RecoveryJobStatus.SUPPRESSED);
            lockedRecoveryJob.setSuppressionReason(LEAD_TIME_REASON);
            auditLogRepository.save(createAuditLog(
                    lockedRecoveryJob.getId(), "SUPPRESS", actorType, actorUserId, null));
            return false;
        }

        return true;
    }

    private AuditLog createAuditLog(
            Long recoveryJobId,
            String action,
            ActorType actorType,
            Long actorUserId,
            String reason) {
        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType("RecoveryJob");
        auditLog.setEntityId(recoveryJobId);
        auditLog.setAction(action);
        auditLog.setActorType(actorType);
        auditLog.setActorUserId(actorUserId);
        auditLog.setReason(reason);
        return auditLog;
    }
}

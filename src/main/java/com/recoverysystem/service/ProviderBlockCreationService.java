package com.recoverysystem.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.ProviderUnavailability;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;
import com.recoverysystem.exception.InvalidBlockIntervalException;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderBlockCreationService {

    private static final String BLOCK_PENDING_REASON = "AWAITING_CONFLICT_RESOLUTION";

    private final ProviderRepository providerRepository;
    private final AppointmentRepository appointmentRepository;
    private final ProviderUnavailabilityRepository providerUnavailabilityRepository;
    private final RecoveryJobSuppressionCascade recoveryJobSuppressionCascade;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public ProviderBlockCreationService(
            ProviderRepository providerRepository,
            AppointmentRepository appointmentRepository,
            ProviderUnavailabilityRepository providerUnavailabilityRepository,
            RecoveryJobSuppressionCascade recoveryJobSuppressionCascade,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper) {
        this.providerRepository = providerRepository;
        this.appointmentRepository = appointmentRepository;
        this.providerUnavailabilityRepository = providerUnavailabilityRepository;
        this.recoveryJobSuppressionCascade = recoveryJobSuppressionCascade;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProviderUnavailability createProviderBlock(
            Long providerId,
            Instant startAt,
            Instant endAt,
            String reason,
            Long actorUserId) {
        providerRepository.findByIdForUpdate(providerId)
                .orElseThrow(() -> new ProviderNotFoundException(providerId));

        if (!startAt.isBefore(endAt)) {
            throw new InvalidBlockIntervalException(startAt, endAt);
        }

        List<Long> conflictingAppointmentIds =
                appointmentRepository.findScheduledOverlappingIds(providerId, startAt, endAt);
        ActorType actorType = actorUserId == null ? ActorType.SYSTEM : ActorType.USER;

        if (!conflictingAppointmentIds.isEmpty()) {
            ProviderUnavailability pendingBlock = createBlock(
                    providerId,
                    startAt,
                    endAt,
                    reason,
                    ProviderUnavailabilityStatus.PENDING,
                    null);
            ProviderUnavailability persistedBlock =
                    providerUnavailabilityRepository.save(pendingBlock);

            AuditLog createAudit = createAuditLog(
                    "ProviderUnavailability",
                    persistedBlock.getId(),
                    "CREATE",
                    actorType,
                    actorUserId,
                    BLOCK_PENDING_REASON);
            createAudit.setNewValues(serializeAuditValues(Map.of(
                    "conflictingAppointmentIds", conflictingAppointmentIds)));
            auditLogRepository.save(createAudit);
            return persistedBlock;
        }

        ProviderUnavailability activeBlock = createBlock(
                providerId,
                startAt,
                endAt,
                reason,
                ProviderUnavailabilityStatus.ACTIVE,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        ProviderUnavailability persistedBlock =
                providerUnavailabilityRepository.save(activeBlock);

        recoveryJobSuppressionCascade.suppressAffectedRecoveryJobs(
                providerId, startAt, endAt, actorType, actorUserId);

        auditLogRepository.save(createAuditLog(
                "ProviderUnavailability",
                persistedBlock.getId(),
                "CREATE",
                actorType,
                actorUserId,
                null));

        return persistedBlock;
    }

    private ProviderUnavailability createBlock(
            Long providerId,
            Instant startAt,
            Instant endAt,
            String reason,
            ProviderUnavailabilityStatus status,
            Instant activatedAt) {
        ProviderUnavailability block = new ProviderUnavailability();
        block.setProviderId(providerId);
        block.setStartAt(startAt);
        block.setEndAt(endAt);
        block.setStatus(status);
        block.setReason(reason);
        block.setActivatedAt(activatedAt);
        return block;
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

    private String serializeAuditValues(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize provider block audit values", exception);
        }
    }
}

package com.recoverysystem.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.ProviderUnavailability;
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.exception.InvalidBlockIntervalException;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
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
    private static final String JOB_SUPPRESSION_REASON = "PROVIDER_BLOCK_ACTIVATED";
    private static final String OFFER_CANCELLATION_REASON = "RECOVERY_JOB_SUPPRESSED";

    private final ProviderRepository providerRepository;
    private final AppointmentRepository appointmentRepository;
    private final ProviderUnavailabilityRepository providerUnavailabilityRepository;
    private final RecoveryJobRepository recoveryJobRepository;
    private final SlotOfferRepository slotOfferRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public ProviderBlockCreationService(
            ProviderRepository providerRepository,
            AppointmentRepository appointmentRepository,
            ProviderUnavailabilityRepository providerUnavailabilityRepository,
            RecoveryJobRepository recoveryJobRepository,
            SlotOfferRepository slotOfferRepository,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper) {
        this.providerRepository = providerRepository;
        this.appointmentRepository = appointmentRepository;
        this.providerUnavailabilityRepository = providerUnavailabilityRepository;
        this.recoveryJobRepository = recoveryJobRepository;
        this.slotOfferRepository = slotOfferRepository;
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

        List<RecoveryJob> recoveryJobs =
                recoveryJobRepository.findOpenOverlappingByProviderForUpdate(
                        providerId, startAt, endAt);
        List<Long> recoveryJobIds = recoveryJobs.stream().map(RecoveryJob::getId).toList();
        List<SlotOffer> slotOffers = recoveryJobIds.isEmpty()
                ? List.of()
                : slotOfferRepository.findOfferedByRecoveryJobIdsForUpdate(recoveryJobIds);

        recoveryJobs.forEach(recoveryJob -> {
            recoveryJob.setStatus(RecoveryJobStatus.SUPPRESSED);
            recoveryJob.setSuppressionReason(JOB_SUPPRESSION_REASON);
        });
        slotOffers.forEach(slotOffer -> slotOffer.setStatus(SlotOfferStatus.CANCELLED));

        auditLogRepository.save(createAuditLog(
                "ProviderUnavailability",
                persistedBlock.getId(),
                "CREATE",
                actorType,
                actorUserId,
                null));
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

package com.recoverysystem.service;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.ProviderUnavailability;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;
import com.recoverysystem.exception.ProviderBlockConflictsUnresolvedException;
import com.recoverysystem.exception.ProviderBlockNotPendingException;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.exception.ProviderUnavailabilityNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderBlockActivationService {

    private final ProviderUnavailabilityRepository providerUnavailabilityRepository;
    private final ProviderRepository providerRepository;
    private final AppointmentRepository appointmentRepository;
    private final RecoveryJobSuppressionCascade recoveryJobSuppressionCascade;
    private final AuditLogRepository auditLogRepository;
    private final EntityManager entityManager;

    public ProviderBlockActivationService(
            ProviderUnavailabilityRepository providerUnavailabilityRepository,
            ProviderRepository providerRepository,
            AppointmentRepository appointmentRepository,
            RecoveryJobSuppressionCascade recoveryJobSuppressionCascade,
            AuditLogRepository auditLogRepository,
            EntityManager entityManager) {
        this.providerUnavailabilityRepository = providerUnavailabilityRepository;
        this.providerRepository = providerRepository;
        this.appointmentRepository = appointmentRepository;
        this.recoveryJobSuppressionCascade = recoveryJobSuppressionCascade;
        this.auditLogRepository = auditLogRepository;
        this.entityManager = entityManager;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProviderUnavailability activateProviderBlock(
            Long providerUnavailabilityId, Long actorUserId) {
        ProviderUnavailability routingBlock = providerUnavailabilityRepository
                .findById(providerUnavailabilityId)
                .orElseThrow(() -> new ProviderUnavailabilityNotFoundException(
                        providerUnavailabilityId));
        Long routedProviderId = routingBlock.getProviderId();
        // Detach immediately after extracting needed values -- otherwise Hibernate's identity map
        // would return this same stale-cached instance from the later locked read below, silently
        // bypassing post-lock revalidation under concurrent modification. See
        // docs/ARCHITECTURE_DECISIONS.md for the full explanation.
        entityManager.detach(routingBlock);

        providerRepository.findByIdForUpdate(routedProviderId)
                .orElseThrow(() -> new ProviderNotFoundException(routedProviderId));

        ProviderUnavailability providerBlock = providerUnavailabilityRepository
                .findByIdForUpdate(providerUnavailabilityId)
                .orElseThrow(() -> new ProviderUnavailabilityNotFoundException(
                        providerUnavailabilityId));
        if (providerBlock.getStatus() != ProviderUnavailabilityStatus.PENDING) {
            throw new ProviderBlockNotPendingException(
                    providerUnavailabilityId, providerBlock.getStatus());
        }

        Long providerId = providerBlock.getProviderId();
        Instant blockStart = providerBlock.getStartAt();
        Instant blockEnd = providerBlock.getEndAt();
        List<Long> conflictingAppointmentIds =
                appointmentRepository.findScheduledOverlappingIds(
                        providerId, blockStart, blockEnd);
        if (!conflictingAppointmentIds.isEmpty()) {
            throw new ProviderBlockConflictsUnresolvedException(
                    providerUnavailabilityId, providerId, conflictingAppointmentIds);
        }

        providerBlock.setStatus(ProviderUnavailabilityStatus.ACTIVE);
        providerBlock.setActivatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));

        ActorType actorType = actorUserId == null ? ActorType.SYSTEM : ActorType.USER;
        recoveryJobSuppressionCascade.suppressAffectedRecoveryJobs(
                providerId, blockStart, blockEnd, actorType, actorUserId);

        AuditLog activationAudit = new AuditLog();
        activationAudit.setEntityType("ProviderUnavailability");
        activationAudit.setEntityId(providerUnavailabilityId);
        activationAudit.setAction("ACTIVATE");
        activationAudit.setActorType(actorType);
        activationAudit.setActorUserId(actorUserId);
        activationAudit.setReason(null);
        auditLogRepository.save(activationAudit);

        return providerBlock;
    }
}

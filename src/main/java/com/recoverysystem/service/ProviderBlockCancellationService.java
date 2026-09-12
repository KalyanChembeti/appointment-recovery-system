package com.recoverysystem.service;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.ProviderUnavailability;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;
import com.recoverysystem.exception.ProviderBlockNotPendingException;
import com.recoverysystem.exception.ProviderUnavailabilityNotFoundException;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderBlockCancellationService {

    private final ProviderUnavailabilityRepository providerUnavailabilityRepository;
    private final AuditLogRepository auditLogRepository;

    public ProviderBlockCancellationService(
            ProviderUnavailabilityRepository providerUnavailabilityRepository,
            AuditLogRepository auditLogRepository) {
        this.providerUnavailabilityRepository = providerUnavailabilityRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProviderUnavailability cancelPendingBlock(
            Long providerUnavailabilityId,
            String cancellationReason,
            Long actorUserId) {
        // This operation only changes the PENDING block. It does not inspect the Provider, and a
        // PENDING block has not changed a RecoveryJob or SlotOffer, so a Provider lock would only
        // add contention with booking work without protecting another state change.
        ProviderUnavailability providerBlock = providerUnavailabilityRepository
                .findByIdForUpdate(providerUnavailabilityId)
                .orElseThrow(() -> new ProviderUnavailabilityNotFoundException(
                        providerUnavailabilityId));

        if (providerBlock.getStatus() != ProviderUnavailabilityStatus.PENDING) {
            throw new ProviderBlockNotPendingException(
                    providerUnavailabilityId, providerBlock.getStatus());
        }

        providerBlock.setStatus(ProviderUnavailabilityStatus.CANCELLED);
        providerBlock.setCancelledAt(Instant.now().truncatedTo(ChronoUnit.MICROS));

        // No original workflow spec defines this added operation's actor rule. It follows the
        // existing convention where a null actor is SYSTEM and a supplied actor is USER.
        ActorType actorType = actorUserId == null ? ActorType.SYSTEM : ActorType.USER;

        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType("ProviderUnavailability");
        auditLog.setEntityId(providerUnavailabilityId);
        auditLog.setAction("CANCEL");
        auditLog.setActorType(actorType);
        auditLog.setActorUserId(actorUserId);
        auditLog.setReason(cancellationReason);
        auditLogRepository.save(auditLog);

        return providerBlock;
    }
}

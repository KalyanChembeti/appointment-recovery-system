package com.recoverysystem.service;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.CancellationReason;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.exception.AppointmentNotFoundException;
import com.recoverysystem.exception.AppointmentNotScheduledException;
import com.recoverysystem.exception.InvalidCancellationReasonException;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentCancellationService {

    private static final String APPOINTMENT_CANCELLED_REASON = "APPOINTMENT_CANCELLED";

    private final AppointmentRepository appointmentRepository;
    private final ProviderRepository providerRepository;
    private final WaitlistReconciliationCascade waitlistReconciliationCascade;
    private final ProviderUnavailabilityRepository providerUnavailabilityRepository;
    private final RecoveryJobRepository recoveryJobRepository;
    private final AuditLogRepository auditLogRepository;

    public AppointmentCancellationService(
            AppointmentRepository appointmentRepository,
            ProviderRepository providerRepository,
            WaitlistReconciliationCascade waitlistReconciliationCascade,
            ProviderUnavailabilityRepository providerUnavailabilityRepository,
            RecoveryJobRepository recoveryJobRepository,
            AuditLogRepository auditLogRepository) {
        this.appointmentRepository = appointmentRepository;
        this.providerRepository = providerRepository;
        this.waitlistReconciliationCascade = waitlistReconciliationCascade;
        this.providerUnavailabilityRepository = providerUnavailabilityRepository;
        this.recoveryJobRepository = recoveryJobRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Appointment cancelAppointment(
            Long appointmentId,
            CancellationReason cancellationReason,
            Long actorUserId) {
        Appointment routingAppointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(appointmentId));

        providerRepository.findByIdForUpdate(routingAppointment.getProviderId())
                .orElseThrow(() -> new ProviderNotFoundException(routingAppointment.getProviderId()));

        Appointment appointment = appointmentRepository.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(appointmentId));

        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new AppointmentNotScheduledException(appointmentId, appointment.getStatus());
        }
        if (cancellationReason == CancellationReason.RESCHEDULED) {
            throw new InvalidCancellationReasonException(appointmentId, cancellationReason);
        }

        ActorType actorType = actorUserId == null ? ActorType.SYSTEM : ActorType.USER;
        waitlistReconciliationCascade.reconcileAnchoredWaitlistEntries(
                appointmentId,
                APPOINTMENT_CANCELLED_REASON,
                actorType,
                actorUserId);

        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setCancellationReason(cancellationReason);
        auditLogRepository.save(createAuditLog(
                "Appointment", appointmentId, "CANCEL", actorType, actorUserId, null));

        if (!providerUnavailabilityRepository.existsOverlappingActiveBlock(
                appointment.getProviderId(), appointment.getStartAt(), appointment.getEndAt())) {
            RecoveryJob recoveryJob = new RecoveryJob();
            recoveryJob.setSourceAppointmentId(appointmentId);
            recoveryJob.setStatus(RecoveryJobStatus.OPEN);
            RecoveryJob persistedRecoveryJob = recoveryJobRepository.save(recoveryJob);
            auditLogRepository.save(createAuditLog(
                    "RecoveryJob",
                    persistedRecoveryJob.getId(),
                    "CREATE",
                    actorType,
                    actorUserId,
                    null));
        }

        return appointment;
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

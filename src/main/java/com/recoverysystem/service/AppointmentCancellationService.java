package com.recoverysystem.service;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.CancellationReason;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.exception.AppointmentNotFoundException;
import com.recoverysystem.exception.AppointmentNotScheduledException;
import com.recoverysystem.exception.InvalidCancellationReasonException;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentCancellationService {

    private static final String APPOINTMENT_CANCELLED_REASON = "APPOINTMENT_CANCELLED";

    private final AppointmentRepository appointmentRepository;
    private final ProviderRepository providerRepository;
    private final WaitlistEntryRepository waitlistEntryRepository;
    private final SlotOfferRepository slotOfferRepository;
    private final ProviderUnavailabilityRepository providerUnavailabilityRepository;
    private final RecoveryJobRepository recoveryJobRepository;
    private final AuditLogRepository auditLogRepository;

    public AppointmentCancellationService(
            AppointmentRepository appointmentRepository,
            ProviderRepository providerRepository,
            WaitlistEntryRepository waitlistEntryRepository,
            SlotOfferRepository slotOfferRepository,
            ProviderUnavailabilityRepository providerUnavailabilityRepository,
            RecoveryJobRepository recoveryJobRepository,
            AuditLogRepository auditLogRepository) {
        this.appointmentRepository = appointmentRepository;
        this.providerRepository = providerRepository;
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.slotOfferRepository = slotOfferRepository;
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

        List<WaitlistEntry> activeWaitlistEntries =
                waitlistEntryRepository.findByCurrentAppointmentIdAndStatusForUpdate(
                        appointmentId, WaitlistEntryStatus.ACTIVE);
        List<Long> waitlistEntryIds =
                activeWaitlistEntries.stream().map(WaitlistEntry::getId).toList();
        List<SlotOffer> offeredSlotOffers = waitlistEntryIds.isEmpty()
                ? List.of()
                : slotOfferRepository.findByWaitlistEntryIdsAndStatusForUpdate(
                        waitlistEntryIds, SlotOfferStatus.OFFERED);

        ActorType actorType = actorUserId == null ? ActorType.SYSTEM : ActorType.USER;

        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setCancellationReason(cancellationReason);
        auditLogRepository.save(createAuditLog(
                "Appointment", appointmentId, "CANCEL", actorType, actorUserId, null));

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
                    APPOINTMENT_CANCELLED_REASON));
        });

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

package com.recoverysystem.service;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.exception.AppointmentNotFoundException;
import com.recoverysystem.exception.OfferExpiredException;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.exception.RecoveryJobNotFoundException;
import com.recoverysystem.exception.RecoveryJobNotOpenException;
import com.recoverysystem.exception.SlotOfferNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchedulerReassignmentService {

    private static final String STAFF_OVERRIDE_REASON = "STAFF_OVERRIDE";

    private final SlotOfferRepository slotOfferRepository;
    private final RecoveryJobRepository recoveryJobRepository;
    private final AppointmentRepository appointmentRepository;
    private final ProviderRepository providerRepository;
    private final AcceptedOfferTerminalStateResolver acceptedOfferTerminalStateResolver;
    private final AppointmentBookingEligibilityValidator appointmentBookingEligibilityValidator;
    private final AuditLogRepository auditLogRepository;
    private final EntityManager entityManager;

    public SchedulerReassignmentService(
            SlotOfferRepository slotOfferRepository,
            RecoveryJobRepository recoveryJobRepository,
            AppointmentRepository appointmentRepository,
            ProviderRepository providerRepository,
            AcceptedOfferTerminalStateResolver acceptedOfferTerminalStateResolver,
            AppointmentBookingEligibilityValidator appointmentBookingEligibilityValidator,
            AuditLogRepository auditLogRepository,
            EntityManager entityManager) {
        this.slotOfferRepository = slotOfferRepository;
        this.recoveryJobRepository = recoveryJobRepository;
        this.appointmentRepository = appointmentRepository;
        this.providerRepository = providerRepository;
        this.acceptedOfferTerminalStateResolver = acceptedOfferTerminalStateResolver;
        this.appointmentBookingEligibilityValidator = appointmentBookingEligibilityValidator;
        this.auditLogRepository = auditLogRepository;
        this.entityManager = entityManager;
    }

    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            noRollbackFor = OfferExpiredException.class)
    public Appointment reassignSlot(
            Long existingSlotOfferId,
            Long replacementPatientId,
            Long newAppointmentTypeId,
            Long actorUserId) {
        SlotOffer routedOffer = slotOfferRepository.findById(existingSlotOfferId)
                .orElseThrow(() -> new SlotOfferNotFoundException(existingSlotOfferId));
        Long recoveryJobId = routedOffer.getRecoveryJobId();
        // Detach immediately after extracting needed values -- otherwise Hibernate's identity map
        // would return this same stale-cached instance from the later locked read below, silently
        // bypassing post-lock revalidation under concurrent modification. See
        // docs/ARCHITECTURE_DECISIONS.md for the full explanation.
        entityManager.detach(routedOffer);

        RecoveryJob routedJob = recoveryJobRepository.findById(recoveryJobId)
                .orElseThrow(() -> new RecoveryJobNotFoundException(recoveryJobId));
        Long sourceAppointmentId = routedJob.getSourceAppointmentId();
        // Detach immediately after extracting needed values -- otherwise Hibernate's identity map
        // would return this same stale-cached instance from the later locked read below, silently
        // bypassing post-lock revalidation under concurrent modification. See
        // docs/ARCHITECTURE_DECISIONS.md for the full explanation.
        entityManager.detach(routedJob);
        Appointment sourceAppointment = appointmentRepository.findById(sourceAppointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(sourceAppointmentId));
        Long offeredProviderId = sourceAppointment.getProviderId();
        Instant offeredStartAt = sourceAppointment.getStartAt();
        Long sourceAppointmentTypeId = sourceAppointment.getAppointmentTypeId();

        Provider offeredProvider = providerRepository.findByIdForUpdate(offeredProviderId)
                .orElseThrow(() -> new ProviderNotFoundException(offeredProviderId));

        RecoveryJob recoveryJob = recoveryJobRepository.findByIdForUpdate(recoveryJobId)
                .orElseThrow(() -> new RecoveryJobNotFoundException(recoveryJobId));
        if (recoveryJob.getStatus() != RecoveryJobStatus.OPEN) {
            throw new RecoveryJobNotOpenException(recoveryJobId, recoveryJob.getStatus());
        }

        // Workflow 12 discovers a job through an untargeted routing query and must recheck for a
        // newly-created offer after locking the job. Here the caller targets this offer directly,
        // and one_offered_per_recovery guarantees it is the only possible OFFERED row for the job.
        Long effectiveAppointmentTypeId = newAppointmentTypeId == null
                ? sourceAppointmentTypeId
                : newAppointmentTypeId;

        SlotOffer slotOffer = slotOfferRepository.findByIdForUpdate(existingSlotOfferId)
                .orElseThrow(() -> new SlotOfferNotFoundException(existingSlotOfferId));
        acceptedOfferTerminalStateResolver.resolve(slotOffer);

        ResolvedBooking booking = appointmentBookingEligibilityValidator.resolveAndValidate(
                offeredProvider, effectiveAppointmentTypeId, offeredStartAt);

        Appointment newAppointment = new Appointment();
        newAppointment.setPatientId(replacementPatientId);
        newAppointment.setProviderId(offeredProviderId);
        newAppointment.setAppointmentTypeId(effectiveAppointmentTypeId);
        newAppointment.setStartAt(offeredStartAt);
        newAppointment.setEndAt(booking.endAt());
        newAppointment.setStatus(AppointmentStatus.SCHEDULED);

        Appointment savedAppointment;
        try {
            savedAppointment = appointmentRepository.save(newAppointment);
            appointmentRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            // This single transaction has no compensating cleanup. A translated overlap failure
            // rolls back the Appointment insert and leaves the offer and job exactly as they were.
            throw BookingConstraintViolationTranslator.translate(exception);
        }

        slotOffer.setStatus(SlotOfferStatus.CANCELLED);
        auditLogRepository.save(createAuditLog(
                "SlotOffer",
                existingSlotOfferId,
                "CANCEL",
                actorUserId,
                STAFF_OVERRIDE_REASON));

        recoveryJob.setStatus(RecoveryJobStatus.FILLED);
        recoveryJob.setFilledAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        auditLogRepository.save(createAuditLog(
                "RecoveryJob",
                recoveryJobId,
                "FILLED",
                actorUserId,
                null));

        auditLogRepository.save(createAuditLog(
                "Appointment",
                savedAppointment.getId(),
                "CREATE",
                actorUserId,
                null));

        return savedAppointment;
    }

    private AuditLog createAuditLog(
            String entityType,
            Long entityId,
            String action,
            Long actorUserId,
            String reason) {
        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType(entityType);
        auditLog.setEntityId(entityId);
        auditLog.setAction(action);
        auditLog.setActorType(ActorType.USER);
        auditLog.setActorUserId(actorUserId);
        auditLog.setReason(reason);
        return auditLog;
    }
}

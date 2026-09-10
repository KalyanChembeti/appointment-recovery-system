package com.recoverysystem.service;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.Provider;
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
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentReschedulingService {

    private static final String APPOINTMENT_RESCHEDULED_REASON = "APPOINTMENT_RESCHEDULED";

    private final AppointmentRepository appointmentRepository;
    private final ProviderRepository providerRepository;
    private final AppointmentBookingEligibilityValidator appointmentBookingEligibilityValidator;
    private final WaitlistEntryRepository waitlistEntryRepository;
    private final SlotOfferRepository slotOfferRepository;
    private final ProviderUnavailabilityRepository providerUnavailabilityRepository;
    private final RecoveryJobRepository recoveryJobRepository;
    private final AuditLogRepository auditLogRepository;

    public AppointmentReschedulingService(
            AppointmentRepository appointmentRepository,
            ProviderRepository providerRepository,
            AppointmentBookingEligibilityValidator appointmentBookingEligibilityValidator,
            WaitlistEntryRepository waitlistEntryRepository,
            SlotOfferRepository slotOfferRepository,
            ProviderUnavailabilityRepository providerUnavailabilityRepository,
            RecoveryJobRepository recoveryJobRepository,
            AuditLogRepository auditLogRepository) {
        this.appointmentRepository = appointmentRepository;
        this.providerRepository = providerRepository;
        this.appointmentBookingEligibilityValidator = appointmentBookingEligibilityValidator;
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.slotOfferRepository = slotOfferRepository;
        this.providerUnavailabilityRepository = providerUnavailabilityRepository;
        this.recoveryJobRepository = recoveryJobRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Appointment rescheduleAppointment(
            Long oldAppointmentId,
            Long newProviderId,
            Long newAppointmentTypeId,
            Instant newStartAt,
            Long actorUserId) {
        Appointment routingAppointment = appointmentRepository.findById(oldAppointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(oldAppointmentId));
        Long oldProviderId = routingAppointment.getProviderId();

        List<Long> providerIds = Stream.of(oldProviderId, newProviderId)
                .distinct()
                .sorted()
                .toList();
        Map<Long, Provider> lockedProviders =
                providerRepository.findAllByIdInForUpdate(providerIds).stream()
                        .collect(Collectors.toMap(Provider::getId, Function.identity()));
        Provider newProvider = lockedProviders.get(newProviderId);
        if (newProvider == null) {
            throw new ProviderNotFoundException(newProviderId);
        }

        ResolvedBooking resolvedBooking =
                appointmentBookingEligibilityValidator.resolveAndValidate(
                        newProvider, newAppointmentTypeId, newStartAt);

        Appointment oldAppointment = appointmentRepository.findByIdForUpdate(oldAppointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(oldAppointmentId));
        if (oldAppointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new AppointmentNotScheduledException(
                    oldAppointmentId, oldAppointment.getStatus());
        }

        List<WaitlistEntry> activeWaitlistEntries =
                waitlistEntryRepository.findByCurrentAppointmentIdAndStatusForUpdate(
                        oldAppointmentId, WaitlistEntryStatus.ACTIVE);
        List<Long> waitlistEntryIds =
                activeWaitlistEntries.stream().map(WaitlistEntry::getId).toList();
        List<SlotOffer> offeredSlotOffers = waitlistEntryIds.isEmpty()
                ? List.of()
                : slotOfferRepository.findByWaitlistEntryIdsAndStatusForUpdate(
                        waitlistEntryIds, SlotOfferStatus.OFFERED);

        Appointment newAppointment = new Appointment();
        newAppointment.setPatientId(routingAppointment.getPatientId());
        newAppointment.setProviderId(newProviderId);
        newAppointment.setAppointmentTypeId(resolvedBooking.appointmentTypeId());
        newAppointment.setStartAt(resolvedBooking.startAt());
        newAppointment.setEndAt(resolvedBooking.endAt());
        newAppointment.setStatus(AppointmentStatus.SCHEDULED);

        Appointment persistedNewAppointment;
        try {
            persistedNewAppointment = appointmentRepository.save(newAppointment);
            appointmentRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw BookingConstraintViolationTranslator.translate(exception);
        }

        ActorType actorType = actorUserId == null ? ActorType.SYSTEM : ActorType.USER;

        auditLogRepository.save(createAuditLog(
                "Appointment",
                persistedNewAppointment.getId(),
                "CREATE",
                actorType,
                actorUserId,
                null));

        oldAppointment.setStatus(AppointmentStatus.CANCELLED);
        oldAppointment.setCancellationReason(CancellationReason.RESCHEDULED);
        oldAppointment.setReplacedByAppointmentId(persistedNewAppointment.getId());
        auditLogRepository.save(createAuditLog(
                "Appointment", oldAppointmentId, "CANCEL", actorType, actorUserId, null));

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
                    APPOINTMENT_RESCHEDULED_REASON));
        });

        if (!providerUnavailabilityRepository.existsOverlappingActiveBlock(
                oldProviderId, oldAppointment.getStartAt(), oldAppointment.getEndAt())) {
            RecoveryJob recoveryJob = new RecoveryJob();
            recoveryJob.setSourceAppointmentId(oldAppointmentId);
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

        return persistedNewAppointment;
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

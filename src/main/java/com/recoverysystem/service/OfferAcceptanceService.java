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
import com.recoverysystem.exception.OfferAcceptanceOwnershipException;
import com.recoverysystem.exception.OfferExpiredException;
import com.recoverysystem.exception.ProviderIntervalOccupiedException;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.exception.ProviderUnavailableException;
import com.recoverysystem.exception.RecoveryJobNotFoundException;
import com.recoverysystem.exception.RecoveryJobNotOpenException;
import com.recoverysystem.exception.SiblingAlreadyFulfilledException;
import com.recoverysystem.exception.SlotOfferNotFoundException;
import com.recoverysystem.exception.WaitlistEntryNotActiveException;
import com.recoverysystem.exception.WaitlistEntryNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfferAcceptanceService {

    private static final String SUPERSEDED_REASON = "SUPERSEDED_BY_ACCEPTANCE";
    private static final String PROVIDER_OCCUPIED_REASON = "PROVIDER_INTERVAL_OCCUPIED";

    private final SlotOfferRepository slotOfferRepository;
    private final RecoveryJobRepository recoveryJobRepository;
    private final WaitlistEntryRepository waitlistEntryRepository;
    private final AppointmentRepository appointmentRepository;
    private final ProviderRepository providerRepository;
    private final ProviderUnavailabilityRepository providerUnavailabilityRepository;
    private final AcceptedOfferTerminalStateResolver acceptedOfferTerminalStateResolver;
    private final SlotOfferCleanupDiscovery slotOfferCleanupDiscovery;
    private final AuditLogRepository auditLogRepository;
    private final EntityManager entityManager;

    public OfferAcceptanceService(
            SlotOfferRepository slotOfferRepository,
            RecoveryJobRepository recoveryJobRepository,
            WaitlistEntryRepository waitlistEntryRepository,
            AppointmentRepository appointmentRepository,
            ProviderRepository providerRepository,
            ProviderUnavailabilityRepository providerUnavailabilityRepository,
            AcceptedOfferTerminalStateResolver acceptedOfferTerminalStateResolver,
            SlotOfferCleanupDiscovery slotOfferCleanupDiscovery,
            AuditLogRepository auditLogRepository,
            EntityManager entityManager) {
        this.slotOfferRepository = slotOfferRepository;
        this.recoveryJobRepository = recoveryJobRepository;
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.appointmentRepository = appointmentRepository;
        this.providerRepository = providerRepository;
        this.providerUnavailabilityRepository = providerUnavailabilityRepository;
        this.acceptedOfferTerminalStateResolver = acceptedOfferTerminalStateResolver;
        this.slotOfferCleanupDiscovery = slotOfferCleanupDiscovery;
        this.auditLogRepository = auditLogRepository;
        this.entityManager = entityManager;
    }

    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            noRollbackFor = {OfferExpiredException.class, ProviderIntervalOccupiedException.class})
    public Appointment acceptOfferTransaction(
            Long slotOfferId, Long patientId, Long actorUserId) {
        SlotOffer routedOffer = slotOfferRepository.findById(slotOfferId)
                .orElseThrow(() -> new SlotOfferNotFoundException(slotOfferId));
        Long recoveryJobId = routedOffer.getRecoveryJobId();
        Long waitlistEntryId = routedOffer.getWaitlistEntryId();
        // Detach immediately after extracting needed values -- otherwise Hibernate's identity map
        // would return this same stale-cached instance from the later locked read below, silently
        // bypassing post-lock revalidation under concurrent modification. See
        // docs/ARCHITECTURE_DECISIONS.md for the full explanation.
        entityManager.detach(routedOffer);

        RecoveryJob routedJob = recoveryJobRepository.findById(recoveryJobId)
                .orElseThrow(() -> new RecoveryJobNotFoundException(recoveryJobId));
        Long offeredAppointmentId = routedJob.getSourceAppointmentId();
        // Detach immediately after extracting needed values -- otherwise Hibernate's identity map
        // would return this same stale-cached instance from the later locked read below, silently
        // bypassing post-lock revalidation under concurrent modification. See
        // docs/ARCHITECTURE_DECISIONS.md for the full explanation.
        entityManager.detach(routedJob);

        WaitlistEntry routedEntry = waitlistEntryRepository.findById(waitlistEntryId)
                .orElseThrow(() -> new WaitlistEntryNotFoundException(waitlistEntryId));
        Long oldAppointmentId = routedEntry.getCurrentAppointmentId();
        Long routedPatientId = routedEntry.getPatientId();
        WaitlistEntryStatus routedEntryStatus = routedEntry.getStatus();
        // Detach immediately after extracting needed values -- otherwise Hibernate's identity map
        // would return this same stale-cached instance from the later locked read below, silently
        // bypassing post-lock revalidation under concurrent modification. See
        // docs/ARCHITECTURE_DECISIONS.md for the full explanation.
        entityManager.detach(routedEntry);

        Appointment routedOldAppointment = appointmentRepository.findById(oldAppointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(oldAppointmentId));
        Long oldProviderId = routedOldAppointment.getProviderId();
        // Detach immediately after extracting needed values -- otherwise Hibernate's identity map
        // would return this same stale-cached instance from the later locked read below, silently
        // bypassing post-lock revalidation under concurrent modification. See
        // docs/ARCHITECTURE_DECISIONS.md for the full explanation.
        entityManager.detach(routedOldAppointment);

        Appointment offeredAppointment = appointmentRepository.findById(offeredAppointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(offeredAppointmentId));

        Long offeredProviderId = offeredAppointment.getProviderId();
        Instant offeredStartAt = offeredAppointment.getStartAt();
        Instant offeredEndAt = offeredAppointment.getEndAt();

        List<Long> providerIds = Stream.of(oldProviderId, offeredProviderId)
                .distinct()
                .sorted()
                .toList();
        List<Provider> lockedProviders = providerRepository.findAllByIdInForUpdate(providerIds);
        boolean offeredProviderFound = lockedProviders.stream()
                .anyMatch(provider -> provider.getId().equals(offeredProviderId));
        if (!offeredProviderFound) {
            throw new ProviderNotFoundException(offeredProviderId);
        }
        if (providerUnavailabilityRepository.existsOverlappingActiveOrPendingBlock(
                offeredProviderId, offeredStartAt, offeredEndAt)) {
            throw new ProviderUnavailableException(
                    offeredProviderId, offeredStartAt, offeredEndAt);
        }

        Appointment oldAppointment = appointmentRepository.findByIdForUpdate(oldAppointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(oldAppointmentId));
        if (oldAppointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new AppointmentNotScheduledException(
                    oldAppointmentId, oldAppointment.getStatus());
        }
        if (!routedPatientId.equals(patientId)) {
            throw new OfferAcceptanceOwnershipException(
                    slotOfferId, patientId, routedPatientId);
        }

        RecoveryJob recoveryJob = recoveryJobRepository.findByIdForUpdate(recoveryJobId)
                .orElseThrow(() -> new RecoveryJobNotFoundException(recoveryJobId));
        if (recoveryJob.getStatus() != RecoveryJobStatus.OPEN) {
            throw new RecoveryJobNotOpenException(recoveryJobId, recoveryJob.getStatus());
        }

        List<WaitlistEntry> activeEntries =
                waitlistEntryRepository.findByCurrentAppointmentIdAndStatusForUpdate(
                        oldAppointmentId, WaitlistEntryStatus.ACTIVE);
        WaitlistEntry acceptedEntry = activeEntries.stream()
                .filter(entry -> entry.getId().equals(waitlistEntryId))
                .findFirst()
                .orElseThrow(() -> new WaitlistEntryNotActiveException(
                        waitlistEntryId, routedEntryStatus));
        if (!acceptedEntry.getPatientId().equals(patientId)) {
            throw new OfferAcceptanceOwnershipException(
                    slotOfferId, patientId, acceptedEntry.getPatientId());
        }

        List<WaitlistEntry> fulfilledEntries =
                waitlistEntryRepository.findFulfilledByCurrentAppointmentId(oldAppointmentId);
        if (!fulfilledEntries.isEmpty()) {
            // This should be unreachable while the old Appointment lock is held because any
            // transaction fulfilling a sibling needs that same lock. It remains a safety net.
            List<Long> fulfilledEntryIds = fulfilledEntries.stream()
                    .map(WaitlistEntry::getId)
                    .sorted()
                    .toList();
            throw new SiblingAlreadyFulfilledException(oldAppointmentId, fulfilledEntryIds);
        }

        List<WaitlistEntry> siblingEntries = activeEntries.stream()
                .filter(entry -> !entry.getId().equals(waitlistEntryId))
                .toList();
        List<Long> siblingEntryIds = siblingEntries.stream()
                .map(WaitlistEntry::getId)
                .toList();

        List<Long> cleanupOfferIds = slotOfferCleanupDiscovery.discoverCleanupOfferIds(
                slotOfferId,
                waitlistEntryId,
                siblingEntryIds,
                patientId,
                oldAppointmentId,
                offeredStartAt,
                offeredEndAt);
        List<Long> allOfferIds = new ArrayList<>(cleanupOfferIds);
        allOfferIds.add(slotOfferId);
        allOfferIds = allOfferIds.stream().distinct().sorted().toList();

        List<SlotOffer> lockedOffers = slotOfferRepository.findAllByIdInForUpdate(allOfferIds);
        SlotOffer acceptedOffer = lockedOffers.stream()
                .filter(offer -> offer.getId().equals(slotOfferId))
                .findFirst()
                .orElseThrow(() -> new SlotOfferNotFoundException(slotOfferId));

        acceptedOfferTerminalStateResolver.resolve(acceptedOffer);

        ActorType actorType = actorUserId == null ? ActorType.SYSTEM : ActorType.USER;
        lockedOffers.stream()
                .filter(offer -> !offer.getId().equals(slotOfferId))
                .forEach(offer -> slotOfferCleanupDiscovery.cleanupIfStillOffered(
                        offer, SUPERSEDED_REASON, actorType, actorUserId));

        List<Long> occupiedAppointmentIds = appointmentRepository.findScheduledOverlappingIds(
                offeredProviderId, offeredStartAt, offeredEndAt);
        if (!occupiedAppointmentIds.isEmpty()) {
            // This must stay before Appointment and WaitlistEntry mutations because this exception
            // does not roll back; only the offer and job terminal states should commit here.
            Instant filledAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
            acceptedOffer.setStatus(SlotOfferStatus.CANCELLED);
            auditLogRepository.save(createAuditLog(
                    "SlotOffer",
                    slotOfferId,
                    "CANCEL",
                    actorType,
                    actorUserId,
                    PROVIDER_OCCUPIED_REASON));

            recoveryJob.setStatus(RecoveryJobStatus.FILLED);
            recoveryJob.setFilledAt(filledAt);
            auditLogRepository.save(createAuditLog(
                    "RecoveryJob",
                    recoveryJobId,
                    "FILLED",
                    actorType,
                    actorUserId,
                    null));
            throw new ProviderIntervalOccupiedException(
                    offeredProviderId, offeredStartAt, offeredEndAt);
        }

        Appointment savedAppointment = saveNewAppointment(
                patientId,
                offeredProviderId,
                acceptedEntry.getAppointmentTypeId(),
                offeredStartAt,
                offeredEndAt);
        auditLogRepository.save(createAuditLog(
                "Appointment",
                savedAppointment.getId(),
                "CREATE",
                actorType,
                actorUserId,
                null));

        oldAppointment.setStatus(AppointmentStatus.CANCELLED);
        oldAppointment.setCancellationReason(CancellationReason.RESCHEDULED);
        oldAppointment.setReplacedByAppointmentId(savedAppointment.getId());
        auditLogRepository.save(createAuditLog(
                "Appointment",
                oldAppointmentId,
                "CANCEL",
                actorType,
                actorUserId,
                null));

        acceptedEntry.setStatus(WaitlistEntryStatus.FULFILLED);
        auditLogRepository.save(createAuditLog(
                "WaitlistEntry",
                acceptedEntry.getId(),
                "FULFILL",
                actorType,
                actorUserId,
                null));

        for (WaitlistEntry siblingEntry : siblingEntries) {
            siblingEntry.setStatus(WaitlistEntryStatus.REMOVED);
            auditLogRepository.save(createAuditLog(
                    "WaitlistEntry",
                    siblingEntry.getId(),
                    "REMOVE",
                    actorType,
                    actorUserId,
                    null));
        }

        Instant acceptedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        acceptedOffer.setStatus(SlotOfferStatus.ACCEPTED);
        acceptedOffer.setAcceptedAt(acceptedAt);
        auditLogRepository.save(createAuditLog(
                "SlotOffer",
                slotOfferId,
                "ACCEPT",
                actorType,
                actorUserId,
                null));

        recoveryJob.setStatus(RecoveryJobStatus.FILLED);
        recoveryJob.setFilledAt(acceptedAt);
        auditLogRepository.save(createAuditLog(
                "RecoveryJob",
                recoveryJobId,
                "FILLED",
                actorType,
                actorUserId,
                null));

        if (!providerUnavailabilityRepository.existsOverlappingActiveBlock(
                oldProviderId, oldAppointment.getStartAt(), oldAppointment.getEndAt())) {
            RecoveryJob oldIntervalJob = new RecoveryJob();
            oldIntervalJob.setSourceAppointmentId(oldAppointmentId);
            oldIntervalJob.setStatus(RecoveryJobStatus.OPEN);
            RecoveryJob savedOldIntervalJob = recoveryJobRepository.save(oldIntervalJob);
            auditLogRepository.save(createAuditLog(
                    "RecoveryJob",
                    savedOldIntervalJob.getId(),
                    "CREATE",
                    actorType,
                    actorUserId,
                    null));
        }

        return savedAppointment;
    }

    private Appointment saveNewAppointment(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Instant startAt,
            Instant endAt) {
        Appointment appointment = new Appointment();
        appointment.setPatientId(patientId);
        appointment.setProviderId(providerId);
        appointment.setAppointmentTypeId(appointmentTypeId);
        appointment.setStartAt(startAt);
        appointment.setEndAt(endAt);
        appointment.setStatus(AppointmentStatus.SCHEDULED);

        try {
            Appointment savedAppointment = appointmentRepository.save(appointment);
            appointmentRepository.flush();
            return savedAppointment;
        } catch (DataIntegrityViolationException exception) {
            throw BookingConstraintViolationTranslator.translate(exception);
        }
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

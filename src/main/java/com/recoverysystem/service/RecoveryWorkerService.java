package com.recoverysystem.service;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.RecoveryJob;
import com.recoverysystem.domain.entity.SchedulingPolicy;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.RecoveryJobStatus;
import com.recoverysystem.domain.enums.RecoveryWorkerOutcome;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import com.recoverysystem.exception.AppointmentNotFoundException;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.exception.RecoveryJobNotFoundException;
import com.recoverysystem.exception.WaitlistEntryNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.RecoveryJobRepository;
import com.recoverysystem.repository.SchedulingPolicyRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecoveryWorkerService {

    private static final String PROVIDER_BLOCK_REASON = "PROVIDER_BLOCK_ACTIVATED";
    private static final String LEAD_TIME_REASON = "LEAD_TIME_CLOSED";

    private final RecoveryJobRepository recoveryJobRepository;
    private final AppointmentRepository appointmentRepository;
    private final ProviderRepository providerRepository;
    private final WaitlistEntryRepository waitlistEntryRepository;
    private final SlotOfferRepository slotOfferRepository;
    private final SchedulingPolicyRepository schedulingPolicyRepository;
    private final AuditLogRepository auditLogRepository;
    private final RecoveryJobEligibilityClassifier eligibilityClassifier;
    private final RecoveryCandidateSelector candidateSelector;
    private final RecoveryCandidateRevalidator candidateRevalidator;
    private final EntityManager entityManager;
    private final ZoneId clinicTimeZone;

    public RecoveryWorkerService(
            RecoveryJobRepository recoveryJobRepository,
            AppointmentRepository appointmentRepository,
            ProviderRepository providerRepository,
            WaitlistEntryRepository waitlistEntryRepository,
            SlotOfferRepository slotOfferRepository,
            SchedulingPolicyRepository schedulingPolicyRepository,
            AuditLogRepository auditLogRepository,
            RecoveryJobEligibilityClassifier eligibilityClassifier,
            RecoveryCandidateSelector candidateSelector,
            RecoveryCandidateRevalidator candidateRevalidator,
            EntityManager entityManager,
            @Value("${recovery-system.clinic.timezone}") String clinicTimeZone) {
        this.recoveryJobRepository = recoveryJobRepository;
        this.appointmentRepository = appointmentRepository;
        this.providerRepository = providerRepository;
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.slotOfferRepository = slotOfferRepository;
        this.schedulingPolicyRepository = schedulingPolicyRepository;
        this.auditLogRepository = auditLogRepository;
        this.eligibilityClassifier = eligibilityClassifier;
        this.candidateSelector = candidateSelector;
        this.candidateRevalidator = candidateRevalidator;
        this.entityManager = entityManager;
        this.clinicTimeZone = ZoneId.of(clinicTimeZone);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RecoveryWorkerOutcome attemptRecovery() {
        List<Long> jobIds = recoveryJobRepository.findOldestOpenJobIdsWithoutOfferedOffer(
                PageRequest.of(0, 1));
        if (jobIds.isEmpty()) {
            return RecoveryWorkerOutcome.NO_OPEN_JOBS;
        }

        Long jobId = jobIds.getFirst();
        RecoveryJob routingJob = recoveryJobRepository.findById(jobId)
                .orElseThrow(() -> new RecoveryJobNotFoundException(jobId));
        Long sourceAppointmentId = routingJob.getSourceAppointmentId();
        // Detach immediately after extracting needed values -- otherwise Hibernate's identity map
        // would return this same stale-cached instance from the later locked read below, silently
        // bypassing post-lock revalidation under concurrent modification. See
        // docs/ARCHITECTURE_DECISIONS.md for the full explanation.
        entityManager.detach(routingJob);
        Appointment sourceAppointment = appointmentRepository.findById(sourceAppointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(sourceAppointmentId));
        Long releasedProviderId = sourceAppointment.getProviderId();
        Instant releasedStartAt = sourceAppointment.getStartAt();
        Instant releasedEndAt = sourceAppointment.getEndAt();
        Long releasedAppointmentTypeId = sourceAppointment.getAppointmentTypeId();

        providerRepository.findByIdForUpdate(releasedProviderId)
                .orElseThrow(() -> new ProviderNotFoundException(releasedProviderId));
        RecoveryJob recoveryJob = recoveryJobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new RecoveryJobNotFoundException(jobId));
        if (recoveryJob.getStatus() != RecoveryJobStatus.OPEN) {
            return RecoveryWorkerOutcome.NO_OPEN_JOBS;
        }
        if (slotOfferRepository.existsByRecoveryJobIdAndStatus(
                jobId, SlotOfferStatus.OFFERED)) {
            return RecoveryWorkerOutcome.OFFER_ALREADY_EXISTS_FOR_JOB;
        }

        boolean jobIsEligible = eligibilityClassifier.classifyAndHandle(
                recoveryJob,
                releasedProviderId,
                releasedStartAt,
                releasedEndAt,
                ActorType.SYSTEM,
                null);
        if (!jobIsEligible) {
            return outcomeAfterClassification(recoveryJob);
        }

        Long candidateId = candidateSelector
                .selectTopCandidate(
                        jobId,
                        releasedAppointmentTypeId,
                        releasedProviderId,
                        releasedStartAt,
                        releasedEndAt)
                .orElse(null);
        if (candidateId == null) {
            recoveryJob.setStatus(RecoveryJobStatus.EXHAUSTED);
            auditLogRepository.save(createAuditLog(
                    "RecoveryJob", jobId, "EXHAUST"));
            return RecoveryWorkerOutcome.NO_ELIGIBLE_CANDIDATE;
        }

        WaitlistEntry candidate = waitlistEntryRepository.findByIdForUpdate(candidateId)
                .orElseThrow(() -> new WaitlistEntryNotFoundException(candidateId));
        LocalDate releasedLocalDate = releasedStartAt.atZone(clinicTimeZone).toLocalDate();
        boolean candidateIsEligible = candidateRevalidator.isStillEligible(
                candidate,
                jobId,
                releasedAppointmentTypeId,
                releasedLocalDate,
                releasedStartAt,
                releasedEndAt);
        if (!candidateIsEligible) {
            return RecoveryWorkerOutcome.CANDIDATE_BECAME_STALE;
        }

        SchedulingPolicy policy = schedulingPolicyRepository.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "SchedulingPolicy singleton row is missing -- V2 migration should have inserted "
                                + "exactly one row and V3's unique index should prevent more than one from ever "
                                + "existing"));
        SlotOffer slotOffer = new SlotOffer();
        slotOffer.setRecoveryJobId(jobId);
        slotOffer.setWaitlistEntryId(candidateId);
        slotOffer.setStatus(SlotOfferStatus.OFFERED);
        slotOffer.setExpiresAt(Instant.now().plus(
                policy.getOfferDurationMinutes(), ChronoUnit.MINUTES));
        SlotOffer savedOffer = slotOfferRepository.save(slotOffer);

        auditLogRepository.save(createAuditLog(
                "SlotOffer", savedOffer.getId(), "CREATE"));
        return RecoveryWorkerOutcome.OFFER_CREATED;
    }

    private RecoveryWorkerOutcome outcomeAfterClassification(RecoveryJob recoveryJob) {
        if (recoveryJob.getStatus() == RecoveryJobStatus.FILLED) {
            return RecoveryWorkerOutcome.RELEASED_INTERVAL_OCCUPIED;
        }
        if (recoveryJob.getStatus() == RecoveryJobStatus.SUPPRESSED
                && PROVIDER_BLOCK_REASON.equals(recoveryJob.getSuppressionReason())) {
            return RecoveryWorkerOutcome.PROVIDER_ACTIVELY_BLOCKED;
        }
        if (recoveryJob.getStatus() == RecoveryJobStatus.SUPPRESSED
                && LEAD_TIME_REASON.equals(recoveryJob.getSuppressionReason())) {
            return RecoveryWorkerOutcome.LEAD_TIME_CLOSED;
        }
        if (recoveryJob.getStatus() == RecoveryJobStatus.OPEN) {
            return RecoveryWorkerOutcome.PROVIDER_PENDING_BLOCKED;
        }
        throw new IllegalStateException(
                "Unexpected recovery job state after eligibility classification: "
                        + recoveryJob.getStatus());
    }

    private AuditLog createAuditLog(String entityType, Long entityId, String action) {
        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType(entityType);
        auditLog.setEntityId(entityId);
        auditLog.setAction(action);
        auditLog.setActorType(ActorType.SYSTEM);
        auditLog.setActorUserId(null);
        auditLog.setReason(null);
        return auditLog;
    }
}

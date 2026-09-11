package com.recoverysystem.service;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.SlotOfferRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

@Service
public class RecoveryCandidateRevalidator {

    private final AppointmentRepository appointmentRepository;
    private final SlotOfferRepository slotOfferRepository;

    public RecoveryCandidateRevalidator(
            AppointmentRepository appointmentRepository,
            SlotOfferRepository slotOfferRepository) {
        this.appointmentRepository = appointmentRepository;
        this.slotOfferRepository = slotOfferRepository;
    }

    public boolean isStillEligible(
            WaitlistEntry lockedCandidate,
            Long recoveryJobId,
            Long releasedAppointmentTypeId,
            LocalDate releasedLocalDate,
            Instant releasedStartAt,
            Instant releasedEndAt) {
        if (lockedCandidate.getStatus() != WaitlistEntryStatus.ACTIVE) {
            return false;
        }
        if (!lockedCandidate.getAppointmentTypeId().equals(releasedAppointmentTypeId)) {
            return false;
        }
        if (releasedLocalDate.isBefore(lockedCandidate.getEarliestAppointmentDate())
                || releasedLocalDate.isAfter(lockedCandidate.getLatestAppointmentDate())) {
            return false;
        }

        Appointment anchor = appointmentRepository
                .findById(lockedCandidate.getCurrentAppointmentId())
                .orElse(null);
        if (anchor == null
                || anchor.getStatus() != AppointmentStatus.SCHEDULED
                || !anchor.getPatientId().equals(lockedCandidate.getPatientId())
                || releasedEndAt.compareTo(anchor.getStartAt()) > 0) {
            return false;
        }

        // There is no separate current-appointment or expected-patient identity check here.
        // No external expected identity is supplied, so the candidate would only be compared
        // with itself.
        if (!appointmentRepository.findScheduledOverlappingIdsForPatient(
                        lockedCandidate.getPatientId(), releasedStartAt, releasedEndAt)
                .isEmpty()) {
            return false;
        }
        if (slotOfferRepository.existsPriorOfferForPatientAndJob(
                recoveryJobId, lockedCandidate.getPatientId())) {
            return false;
        }

        return true;
    }
}

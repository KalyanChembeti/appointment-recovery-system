package com.recoverysystem.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.domain.enums.TimeOfDayPreference;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import com.recoverysystem.exception.AppointmentNotFoundException;
import com.recoverysystem.exception.AppointmentTypeNotFoundException;
import com.recoverysystem.exception.InvalidWaitlistDateRangeException;
import com.recoverysystem.exception.PreferredProviderSpecialtyMismatchException;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.exception.WaitlistAnchorNotScheduledException;
import com.recoverysystem.exception.WaitlistEntryAnchorMismatchException;
import com.recoverysystem.exception.WaitlistEntryNotActiveException;
import com.recoverysystem.exception.WaitlistEntryNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WaitlistEntryModificationService {

    private final WaitlistEntryRepository waitlistEntryRepository;
    private final AppointmentRepository appointmentRepository;
    private final ProviderRepository providerRepository;
    private final AppointmentTypeRepository appointmentTypeRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public WaitlistEntryModificationService(
            WaitlistEntryRepository waitlistEntryRepository,
            AppointmentRepository appointmentRepository,
            ProviderRepository providerRepository,
            AppointmentTypeRepository appointmentTypeRepository,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            EntityManager entityManager) {
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.appointmentRepository = appointmentRepository;
        this.providerRepository = providerRepository;
        this.appointmentTypeRepository = appointmentTypeRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WaitlistEntry modifyWaitlistEntry(
            Long waitlistEntryId,
            LocalDate newEarliestDate,
            LocalDate newLatestDate,
            Long newPreferredProviderId,
            TimeOfDayPreference newPreferredTimeOfDay,
            Long actorUserId) {
        WaitlistEntry routingEntry = waitlistEntryRepository.findById(waitlistEntryId)
                .orElseThrow(() -> new WaitlistEntryNotFoundException(waitlistEntryId));
        Long routedAppointmentId = routingEntry.getCurrentAppointmentId();
        // Detach immediately after extracting needed values -- otherwise Hibernate's identity map
        // would return this same stale-cached instance from the later locked read below, silently
        // bypassing post-lock revalidation under concurrent modification. See
        // docs/ARCHITECTURE_DECISIONS.md for the full explanation.
        entityManager.detach(routingEntry);

        Appointment appointment = appointmentRepository.findByIdForUpdate(routedAppointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(routedAppointmentId));
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new WaitlistAnchorNotScheduledException(
                    routedAppointmentId, appointment.getStatus());
        }

        WaitlistEntry waitlistEntry = waitlistEntryRepository.findByIdForUpdate(waitlistEntryId)
                .orElseThrow(() -> new WaitlistEntryNotFoundException(waitlistEntryId));
        if (waitlistEntry.getStatus() != WaitlistEntryStatus.ACTIVE) {
            throw new WaitlistEntryNotActiveException(
                    waitlistEntryId, waitlistEntry.getStatus());
        }
        if (!routedAppointmentId.equals(waitlistEntry.getCurrentAppointmentId())) {
            throw new WaitlistEntryAnchorMismatchException(
                    waitlistEntryId,
                    routedAppointmentId,
                    waitlistEntry.getCurrentAppointmentId());
        }

        if (newPreferredProviderId != null) {
            Provider preferredProvider = providerRepository.findById(newPreferredProviderId)
                    .orElseThrow(() -> new ProviderNotFoundException(newPreferredProviderId));
            AppointmentType appointmentType = appointmentTypeRepository
                    .findById(waitlistEntry.getAppointmentTypeId())
                    .orElseThrow(() -> new AppointmentTypeNotFoundException(
                            waitlistEntry.getAppointmentTypeId()));
            if (!preferredProvider.getSpecialtyId().equals(appointmentType.getSpecialtyId())) {
                throw new PreferredProviderSpecialtyMismatchException(
                        newPreferredProviderId,
                        preferredProvider.getSpecialtyId(),
                        appointmentType.getId(),
                        appointmentType.getSpecialtyId());
            }
        }

        if (newEarliestDate.isAfter(newLatestDate)) {
            throw new InvalidWaitlistDateRangeException(newEarliestDate, newLatestDate);
        }

        TimeOfDayPreference persistedTimeOfDay = newPreferredTimeOfDay == null
                ? TimeOfDayPreference.ANY
                : newPreferredTimeOfDay;
        Map<String, Object> oldValues = auditValues(waitlistEntry);

        waitlistEntry.setEarliestAppointmentDate(newEarliestDate);
        waitlistEntry.setLatestAppointmentDate(newLatestDate);
        waitlistEntry.setPreferredProviderId(newPreferredProviderId);
        waitlistEntry.setPreferredTimeOfDay(persistedTimeOfDay);

        Map<String, Object> newValues = auditValues(waitlistEntry);
        ActorType actorType = actorUserId == null ? ActorType.SYSTEM : ActorType.USER;

        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType("WaitlistEntry");
        auditLog.setEntityId(waitlistEntryId);
        auditLog.setAction("UPDATE");
        auditLog.setOldValues(serializeAuditValues(oldValues));
        auditLog.setNewValues(serializeAuditValues(newValues));
        auditLog.setActorType(actorType);
        auditLog.setActorUserId(actorUserId);
        auditLog.setReason(null);
        auditLogRepository.save(auditLog);

        return waitlistEntry;
    }

    private Map<String, Object> auditValues(WaitlistEntry waitlistEntry) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("earliestAppointmentDate", waitlistEntry.getEarliestAppointmentDate());
        values.put("latestAppointmentDate", waitlistEntry.getLatestAppointmentDate());
        values.put("preferredProviderId", waitlistEntry.getPreferredProviderId());
        values.put("preferredTimeOfDay", waitlistEntry.getPreferredTimeOfDay());
        return values;
    }

    private String serializeAuditValues(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize waitlist entry audit values", exception);
        }
    }
}

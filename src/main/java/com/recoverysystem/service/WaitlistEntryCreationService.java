package com.recoverysystem.service;

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
import com.recoverysystem.exception.WaitlistAnchorOwnershipException;
import com.recoverysystem.exception.WaitlistAppointmentTypeMismatchException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WaitlistEntryCreationService {

    private final AppointmentRepository appointmentRepository;
    private final WaitlistEntryRepository waitlistEntryRepository;
    private final ProviderRepository providerRepository;
    private final AppointmentTypeRepository appointmentTypeRepository;
    private final AuditLogRepository auditLogRepository;

    public WaitlistEntryCreationService(
            AppointmentRepository appointmentRepository,
            WaitlistEntryRepository waitlistEntryRepository,
            ProviderRepository providerRepository,
            AppointmentTypeRepository appointmentTypeRepository,
            AuditLogRepository auditLogRepository) {
        this.appointmentRepository = appointmentRepository;
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.providerRepository = providerRepository;
        this.appointmentTypeRepository = appointmentTypeRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WaitlistEntry createWaitlistEntry(
            Long patientId,
            Long currentAppointmentId,
            Long appointmentTypeId,
            LocalDate earliestDate,
            LocalDate latestDate,
            Long preferredProviderId,
            TimeOfDayPreference preferredTimeOfDay) {
        Appointment appointment = appointmentRepository.findByIdForUpdate(currentAppointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(currentAppointmentId));

        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new WaitlistAnchorNotScheduledException(
                    currentAppointmentId, appointment.getStatus());
        }
        if (!appointment.getPatientId().equals(patientId)) {
            throw new WaitlistAnchorOwnershipException(
                    currentAppointmentId, patientId, appointment.getPatientId());
        }
        if (!appointment.getAppointmentTypeId().equals(appointmentTypeId)) {
            throw new WaitlistAppointmentTypeMismatchException(
                    currentAppointmentId,
                    appointmentTypeId,
                    appointment.getAppointmentTypeId());
        }

        if (preferredProviderId != null) {
            Provider preferredProvider = providerRepository.findById(preferredProviderId)
                    .orElseThrow(() -> new ProviderNotFoundException(preferredProviderId));
            AppointmentType appointmentType = appointmentTypeRepository.findById(appointmentTypeId)
                    .orElseThrow(() -> new AppointmentTypeNotFoundException(appointmentTypeId));
            if (!preferredProvider.getSpecialtyId().equals(appointmentType.getSpecialtyId())) {
                throw new PreferredProviderSpecialtyMismatchException(
                        preferredProviderId,
                        preferredProvider.getSpecialtyId(),
                        appointmentTypeId,
                        appointmentType.getSpecialtyId());
            }
        }

        if (earliestDate.isAfter(latestDate)) {
            throw new InvalidWaitlistDateRangeException(earliestDate, latestDate);
        }

        WaitlistEntry waitlistEntry = new WaitlistEntry();
        waitlistEntry.setPatientId(patientId);
        waitlistEntry.setCurrentAppointmentId(currentAppointmentId);
        waitlistEntry.setAppointmentTypeId(appointmentTypeId);
        waitlistEntry.setPreferredProviderId(preferredProviderId);
        waitlistEntry.setEarliestAppointmentDate(earliestDate);
        waitlistEntry.setLatestAppointmentDate(latestDate);
        waitlistEntry.setPreferredTimeOfDay(
                preferredTimeOfDay == null ? TimeOfDayPreference.ANY : preferredTimeOfDay);
        waitlistEntry.setStatus(WaitlistEntryStatus.ACTIVE);
        WaitlistEntry persistedWaitlistEntry = waitlistEntryRepository.save(waitlistEntry);

        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType("WaitlistEntry");
        auditLog.setEntityId(persistedWaitlistEntry.getId());
        auditLog.setAction("CREATE");
        auditLog.setActorType(ActorType.USER);
        auditLog.setActorUserId(patientId);
        auditLogRepository.save(auditLog);

        return persistedWaitlistEntry;
    }
}

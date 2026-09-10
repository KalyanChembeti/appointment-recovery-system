package com.recoverysystem.service;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DirectBookingService {

    private final ProviderRepository providerRepository;
    private final AppointmentBookingEligibilityValidator appointmentBookingEligibilityValidator;
    private final AppointmentRepository appointmentRepository;
    private final AuditLogRepository auditLogRepository;

    public DirectBookingService(
            ProviderRepository providerRepository,
            AppointmentBookingEligibilityValidator appointmentBookingEligibilityValidator,
            AppointmentRepository appointmentRepository,
            AuditLogRepository auditLogRepository) {
        this.providerRepository = providerRepository;
        this.appointmentBookingEligibilityValidator = appointmentBookingEligibilityValidator;
        this.appointmentRepository = appointmentRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Appointment bookAppointment(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Instant startAt,
            Long actorUserId) {
        Provider provider = providerRepository.findByIdForUpdate(providerId)
                .orElseThrow(() -> new ProviderNotFoundException(providerId));
        ResolvedBooking resolvedBooking =
                appointmentBookingEligibilityValidator.resolveAndValidate(
                        provider, appointmentTypeId, startAt);

        Appointment appointment = new Appointment();
        appointment.setPatientId(patientId);
        appointment.setProviderId(providerId);
        appointment.setAppointmentTypeId(resolvedBooking.appointmentTypeId());
        appointment.setStartAt(resolvedBooking.startAt());
        appointment.setEndAt(resolvedBooking.endAt());
        appointment.setStatus(AppointmentStatus.SCHEDULED);

        Appointment persistedAppointment;
        try {
            persistedAppointment = appointmentRepository.save(appointment);
            appointmentRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw BookingConstraintViolationTranslator.translate(exception);
        }

        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType("Appointment");
        auditLog.setEntityId(persistedAppointment.getId());
        auditLog.setAction("CREATE");
        auditLog.setActorType(actorUserId == null ? ActorType.SYSTEM : ActorType.USER);
        auditLog.setActorUserId(actorUserId);
        auditLogRepository.save(auditLog);

        return persistedAppointment;
    }
}

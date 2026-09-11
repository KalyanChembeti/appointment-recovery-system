package com.recoverysystem.service;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.exception.AppointmentNotFoundException;
import com.recoverysystem.exception.AppointmentNotScheduledException;
import com.recoverysystem.exception.AppointmentNotYetStartedException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AuditLogRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentCompletionService {

    private static final String APPOINTMENT_COMPLETED_REASON = "APPOINTMENT_COMPLETED";

    private final AppointmentRepository appointmentRepository;
    private final WaitlistReconciliationCascade waitlistReconciliationCascade;
    private final AuditLogRepository auditLogRepository;

    public AppointmentCompletionService(
            AppointmentRepository appointmentRepository,
            WaitlistReconciliationCascade waitlistReconciliationCascade,
            AuditLogRepository auditLogRepository) {
        this.appointmentRepository = appointmentRepository;
        this.waitlistReconciliationCascade = waitlistReconciliationCascade;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Appointment completeAppointment(Long appointmentId, Long actorUserId) {
        Appointment appointment = appointmentRepository.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(appointmentId));

        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new AppointmentNotScheduledException(appointmentId, appointment.getStatus());
        }
        if (!appointment.getStartAt().isBefore(Instant.now())) {
            throw new AppointmentNotYetStartedException(appointmentId, appointment.getStartAt());
        }

        ActorType actorType = actorUserId == null ? ActorType.SYSTEM : ActorType.USER;
        waitlistReconciliationCascade.reconcileAnchoredWaitlistEntries(
                appointmentId,
                APPOINTMENT_COMPLETED_REASON,
                actorType,
                actorUserId);

        appointment.setStatus(AppointmentStatus.COMPLETED);

        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType("Appointment");
        auditLog.setEntityId(appointmentId);
        auditLog.setAction("COMPLETED");
        auditLog.setActorType(actorType);
        auditLog.setActorUserId(actorUserId);
        auditLog.setReason(null);
        auditLogRepository.save(auditLog);

        return appointment;
    }
}

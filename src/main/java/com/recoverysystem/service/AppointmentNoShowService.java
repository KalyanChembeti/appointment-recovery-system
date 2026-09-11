package com.recoverysystem.service;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.exception.AppointmentNotFoundException;
import com.recoverysystem.exception.AppointmentNotScheduledException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentNoShowService {

    private static final String APPOINTMENT_NOSHOW_REASON = "APPOINTMENT_NOSHOW";

    private final AppointmentRepository appointmentRepository;
    private final WaitlistReconciliationCascade waitlistReconciliationCascade;
    private final AuditLogRepository auditLogRepository;

    public AppointmentNoShowService(
            AppointmentRepository appointmentRepository,
            WaitlistReconciliationCascade waitlistReconciliationCascade,
            AuditLogRepository auditLogRepository) {
        this.appointmentRepository = appointmentRepository;
        this.waitlistReconciliationCascade = waitlistReconciliationCascade;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Appointment markNoShow(Long appointmentId, Long actorUserId) {
        Appointment appointment = appointmentRepository.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(appointmentId));

        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new AppointmentNotScheduledException(appointmentId, appointment.getStatus());
        }

        waitlistReconciliationCascade.reconcileAnchoredWaitlistEntries(
                appointmentId,
                APPOINTMENT_NOSHOW_REASON,
                ActorType.USER,
                actorUserId);

        appointment.setStatus(AppointmentStatus.NO_SHOW);

        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType("Appointment");
        auditLog.setEntityId(appointmentId);
        auditLog.setAction("NO_SHOW");
        auditLog.setActorType(ActorType.USER);
        auditLog.setActorUserId(actorUserId);
        auditLog.setReason(null);
        auditLogRepository.save(auditLog);

        return appointment;
    }
}

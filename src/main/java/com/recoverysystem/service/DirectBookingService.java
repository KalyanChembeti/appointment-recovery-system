package com.recoverysystem.service;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.ProviderSchedule;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.AppointmentStatus;
import com.recoverysystem.exception.AppointmentTypeNotFoundException;
import com.recoverysystem.exception.AppointmentTypeSpecialtyMismatchException;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.exception.ProviderUnavailableException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderScheduleRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DirectBookingService {

    private final ProviderRepository providerRepository;
    private final ProviderUnavailabilityRepository providerUnavailabilityRepository;
    private final ProviderScheduleRepository providerScheduleRepository;
    private final AppointmentTypeRepository appointmentTypeRepository;
    private final AppointmentRepository appointmentRepository;
    private final AuditLogRepository auditLogRepository;
    private final ZoneId clinicTimeZone;

    public DirectBookingService(
            ProviderRepository providerRepository,
            ProviderUnavailabilityRepository providerUnavailabilityRepository,
            ProviderScheduleRepository providerScheduleRepository,
            AppointmentTypeRepository appointmentTypeRepository,
            AppointmentRepository appointmentRepository,
            AuditLogRepository auditLogRepository,
            @Value("${recovery-system.clinic.timezone}") String clinicTimeZone) {
        this.providerRepository = providerRepository;
        this.providerUnavailabilityRepository = providerUnavailabilityRepository;
        this.providerScheduleRepository = providerScheduleRepository;
        this.appointmentTypeRepository = appointmentTypeRepository;
        this.appointmentRepository = appointmentRepository;
        this.auditLogRepository = auditLogRepository;
        this.clinicTimeZone = ZoneId.of(clinicTimeZone);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Appointment bookAppointment(
            Long patientId,
            Long providerId,
            Long appointmentTypeId,
            Instant startAt,
            Instant endAt,
            Long actorUserId) {
        Provider provider = providerRepository.findByIdForUpdate(providerId)
                .orElseThrow(() -> new ProviderNotFoundException(providerId));
        AppointmentType appointmentType = appointmentTypeRepository.findById(appointmentTypeId)
                .orElseThrow(() -> new AppointmentTypeNotFoundException(appointmentTypeId));
        if (!provider.getSpecialtyId().equals(appointmentType.getSpecialtyId())) {
            throw new AppointmentTypeSpecialtyMismatchException(
                    appointmentTypeId,
                    appointmentType.getSpecialtyId(),
                    providerId,
                    provider.getSpecialtyId());
        }

        boolean hasBlockingUnavailability =
                providerUnavailabilityRepository.existsOverlappingActiveOrPendingBlock(
                        providerId, startAt, endAt);
        if (hasBlockingUnavailability || !isWithinWorkingHours(providerId, startAt, endAt)) {
            throw new ProviderUnavailableException(providerId, startAt, endAt);
        }

        Appointment appointment = new Appointment();
        appointment.setPatientId(patientId);
        appointment.setProviderId(providerId);
        appointment.setAppointmentTypeId(appointmentTypeId);
        appointment.setStartAt(startAt);
        appointment.setEndAt(endAt);
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

    private boolean isWithinWorkingHours(Long providerId, Instant startAt, Instant endAt) {
        if (!startAt.isBefore(endAt)) {
            return false;
        }

        ZonedDateTime localStart = startAt.atZone(clinicTimeZone);
        ZonedDateTime localEnd = endAt.atZone(clinicTimeZone);
        LocalDate localStartDate = localStart.toLocalDate();

        // Appointments are assumed to fall entirely within one local calendar day.
        // Cross-midnight requests fail closed because cross-day schedule matching is unspecified.
        if (!localStartDate.equals(localEnd.toLocalDate())) {
            return false;
        }

        List<ProviderSchedule> schedules =
                providerScheduleRepository.findActiveByProviderIdAndDayOfWeek(
                        providerId, localStart.getDayOfWeek());
        LocalTime requestedStart = localStart.toLocalTime();
        LocalTime requestedEnd = localEnd.toLocalTime();

        return schedules.stream().anyMatch(schedule ->
                !requestedStart.isBefore(schedule.getStartTime())
                        && !requestedEnd.isAfter(schedule.getEndTime()));
    }
}

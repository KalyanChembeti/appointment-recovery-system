package com.recoverysystem.service;

import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.ProviderSchedule;
import com.recoverysystem.exception.AppointmentTypeNotFoundException;
import com.recoverysystem.exception.ProviderNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderScheduleRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AvailabilityQueryService {

    private final ProviderRepository providerRepository;
    private final AppointmentTypeRepository appointmentTypeRepository;
    private final ProviderScheduleRepository providerScheduleRepository;
    private final AppointmentRepository appointmentRepository;
    private final ProviderUnavailabilityRepository providerUnavailabilityRepository;
    private final ZoneId clinicTimeZone;

    public AvailabilityQueryService(
            ProviderRepository providerRepository,
            AppointmentTypeRepository appointmentTypeRepository,
            ProviderScheduleRepository providerScheduleRepository,
            AppointmentRepository appointmentRepository,
            ProviderUnavailabilityRepository providerUnavailabilityRepository,
            @Value("${recovery-system.clinic.timezone}") String clinicTimeZone) {
        this.providerRepository = providerRepository;
        this.appointmentTypeRepository = appointmentTypeRepository;
        this.providerScheduleRepository = providerScheduleRepository;
        this.appointmentRepository = appointmentRepository;
        this.providerUnavailabilityRepository = providerUnavailabilityRepository;
        this.clinicTimeZone = ZoneId.of(clinicTimeZone);
    }

    @Transactional(readOnly = true)
    public List<TimeSlot> findAvailableSlots(
            Long providerId, Long appointmentTypeId, LocalDate date) {
        providerRepository.findById(providerId)
                .orElseThrow(() -> new ProviderNotFoundException(providerId));
        AppointmentType appointmentType = appointmentTypeRepository.findById(appointmentTypeId)
                .orElseThrow(() -> new AppointmentTypeNotFoundException(appointmentTypeId));

        Duration slotDuration = Duration.ofMinutes(appointmentType.getDurationMinutes());
        List<ProviderSchedule> schedules =
                providerScheduleRepository.findActiveByProviderIdAndDayOfWeek(
                        providerId, date.getDayOfWeek());

        return schedules.stream()
                .flatMap(schedule -> slotsWithinSchedule(
                        providerId, date, schedule, slotDuration).stream())
                .distinct()
                .sorted(Comparator.comparing(TimeSlot::startAt)
                        .thenComparing(TimeSlot::endAt))
                .toList();
    }

    private List<TimeSlot> slotsWithinSchedule(
            Long providerId,
            LocalDate date,
            ProviderSchedule schedule,
            Duration slotDuration) {
        Instant scheduleStart = date.atTime(schedule.getStartTime())
                .atZone(clinicTimeZone)
                .toInstant();
        Instant scheduleEnd = date.atTime(schedule.getEndTime())
                .atZone(clinicTimeZone)
                .toInstant();
        List<TimeSlot> slots = new ArrayList<>();

        for (Instant startAt = scheduleStart;
                !startAt.plus(slotDuration).isAfter(scheduleEnd);
                startAt = startAt.plus(slotDuration)) {
            Instant endAt = startAt.plus(slotDuration);
            if (appointmentRepository.findScheduledOverlappingIds(
                            providerId, startAt, endAt)
                    .isEmpty()
                    && !providerUnavailabilityRepository.existsOverlappingActiveOrPendingBlock(
                            providerId, startAt, endAt)) {
                slots.add(new TimeSlot(startAt, endAt));
            }
        }
        return slots;
    }
}

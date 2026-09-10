package com.recoverysystem.service;

import com.recoverysystem.domain.entity.AppointmentType;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.ProviderSchedule;
import com.recoverysystem.exception.AppointmentTypeNotFoundException;
import com.recoverysystem.exception.AppointmentTypeSpecialtyMismatchException;
import com.recoverysystem.exception.ProviderUnavailableException;
import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.ProviderScheduleRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AppointmentBookingEligibilityValidator {

    private final AppointmentTypeRepository appointmentTypeRepository;
    private final ProviderUnavailabilityRepository providerUnavailabilityRepository;
    private final ProviderScheduleRepository providerScheduleRepository;
    private final ZoneId clinicTimeZone;

    public AppointmentBookingEligibilityValidator(
            AppointmentTypeRepository appointmentTypeRepository,
            ProviderUnavailabilityRepository providerUnavailabilityRepository,
            ProviderScheduleRepository providerScheduleRepository,
            @Value("${recovery-system.clinic.timezone}") String clinicTimeZone) {
        this.appointmentTypeRepository = appointmentTypeRepository;
        this.providerUnavailabilityRepository = providerUnavailabilityRepository;
        this.providerScheduleRepository = providerScheduleRepository;
        this.clinicTimeZone = ZoneId.of(clinicTimeZone);
    }

    public ResolvedBooking resolveAndValidate(
            Provider lockedProvider, Long appointmentTypeId, Instant startAt) {
        AppointmentType appointmentType = appointmentTypeRepository.findById(appointmentTypeId)
                .orElseThrow(() -> new AppointmentTypeNotFoundException(appointmentTypeId));
        Instant endAt = startAt.plus(
                appointmentType.getDurationMinutes(), ChronoUnit.MINUTES);

        if (!appointmentType.getSpecialtyId().equals(lockedProvider.getSpecialtyId())) {
            throw new AppointmentTypeSpecialtyMismatchException(
                    appointmentTypeId,
                    appointmentType.getSpecialtyId(),
                    lockedProvider.getId(),
                    lockedProvider.getSpecialtyId());
        }

        boolean hasBlockingUnavailability =
                providerUnavailabilityRepository.existsOverlappingActiveOrPendingBlock(
                        lockedProvider.getId(), startAt, endAt);
        if (hasBlockingUnavailability
                || !isWithinWorkingHours(lockedProvider.getId(), startAt, endAt)) {
            throw new ProviderUnavailableException(lockedProvider.getId(), startAt, endAt);
        }

        return new ResolvedBooking(appointmentTypeId, startAt, endAt);
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

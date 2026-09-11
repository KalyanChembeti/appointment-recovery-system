package com.recoverysystem.service;

import com.recoverysystem.repository.WaitlistEntryRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class RecoveryCandidateSelector {

    private final WaitlistEntryRepository waitlistEntryRepository;
    private final ZoneId clinicTimeZone;

    public RecoveryCandidateSelector(
            WaitlistEntryRepository waitlistEntryRepository,
            @Value("${recovery-system.clinic.timezone}") String clinicTimeZone) {
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.clinicTimeZone = ZoneId.of(clinicTimeZone);
    }

    public Optional<Long> selectTopCandidate(
            Long recoveryJobId,
            Long releasedAppointmentTypeId,
            Long releasedProviderId,
            Instant releasedStartAt,
            Instant releasedEndAt) {
        ZonedDateTime localStart = releasedStartAt.atZone(clinicTimeZone);
        LocalDate releasedLocalDate = localStart.toLocalDate();
        boolean releasedIsLocalMorning = localStart.toLocalTime().isBefore(LocalTime.NOON);

        List<Long> candidateIds = waitlistEntryRepository.findRankedEligibleCandidateIds(
                recoveryJobId,
                releasedAppointmentTypeId,
                releasedProviderId,
                releasedLocalDate,
                releasedStartAt,
                releasedEndAt,
                releasedIsLocalMorning,
                PageRequest.of(0, 1));
        return candidateIds.stream().findFirst();
    }
}

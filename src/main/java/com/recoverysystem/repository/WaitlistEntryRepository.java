package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WaitlistEntry w WHERE w.id = :id")
    Optional<WaitlistEntry> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WaitlistEntry w WHERE w.currentAppointmentId = :appointmentId "
            + "AND w.status = :status ORDER BY w.id ASC")
    List<WaitlistEntry> findByCurrentAppointmentIdAndStatusForUpdate(
            @Param("appointmentId") Long appointmentId,
            @Param("status") WaitlistEntryStatus status);

    @Query("SELECT w FROM WaitlistEntry w WHERE w.currentAppointmentId = :appointmentId "
            + "AND w.status = com.recoverysystem.domain.enums.WaitlistEntryStatus.FULFILLED")
    List<WaitlistEntry> findFulfilledByCurrentAppointmentId(
            @Param("appointmentId") Long appointmentId);

    @Query("""
            SELECT we.id
            FROM WaitlistEntry we
            WHERE we.status = com.recoverysystem.domain.enums.WaitlistEntryStatus.ACTIVE
              AND we.appointmentTypeId = :releasedAppointmentTypeId
              AND we.earliestAppointmentDate <= :releasedLocalDate
              AND we.latestAppointmentDate >= :releasedLocalDate
              AND EXISTS (
                  SELECT 1 FROM Appointment anchor
                  WHERE anchor.id = we.currentAppointmentId
                    AND anchor.status = com.recoverysystem.domain.enums.AppointmentStatus.SCHEDULED
                    AND anchor.patientId = we.patientId
                    AND :releasedEndAt <= anchor.startAt
              )
              AND NOT EXISTS (
                  SELECT 1 FROM Appointment conflict
                  WHERE conflict.patientId = we.patientId
                    AND conflict.status = com.recoverysystem.domain.enums.AppointmentStatus.SCHEDULED
                    AND conflict.startAt < :releasedEndAt
                    AND conflict.endAt > :releasedStartAt
              )
              AND NOT EXISTS (
                  SELECT 1 FROM SlotOffer priorOffer
                  WHERE priorOffer.recoveryJobId = :recoveryJobId
                    AND priorOffer.waitlistEntryId IN (
                        SELECT otherEntry.id FROM WaitlistEntry otherEntry
                        WHERE otherEntry.patientId = we.patientId
                    )
              )
            ORDER BY
              we.createdAt ASC,
              CASE
                WHEN we.preferredTimeOfDay = com.recoverysystem.domain.enums.TimeOfDayPreference.ANY THEN 0
                WHEN :releasedIsLocalMorning = TRUE
                     AND we.preferredTimeOfDay = com.recoverysystem.domain.enums.TimeOfDayPreference.MORNING THEN 0
                WHEN :releasedIsLocalMorning = FALSE
                     AND we.preferredTimeOfDay = com.recoverysystem.domain.enums.TimeOfDayPreference.AFTERNOON THEN 0
                ELSE 1
              END ASC,
              CASE
                WHEN we.preferredProviderId IS NULL THEN 0
                WHEN we.preferredProviderId = :releasedProviderId THEN 0
                ELSE 1
              END ASC,
              we.id ASC
            """)
    List<Long> findRankedEligibleCandidateIds(
            @Param("recoveryJobId") Long recoveryJobId,
            @Param("releasedAppointmentTypeId") Long releasedAppointmentTypeId,
            @Param("releasedProviderId") Long releasedProviderId,
            @Param("releasedLocalDate") LocalDate releasedLocalDate,
            @Param("releasedStartAt") Instant releasedStartAt,
            @Param("releasedEndAt") Instant releasedEndAt,
            @Param("releasedIsLocalMorning") boolean releasedIsLocalMorning,
            Pageable pageable);
}

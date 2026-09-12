package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlotOfferRepository extends JpaRepository<SlotOffer, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SlotOffer s WHERE s.id = :id")
    Optional<SlotOffer> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SlotOffer s WHERE s.id IN :ids ORDER BY s.id ASC")
    List<SlotOffer> findAllByIdInForUpdate(@Param("ids") List<Long> ids);

    @Query("SELECT s.id FROM SlotOffer s WHERE s.status = "
            + "com.recoverysystem.domain.enums.SlotOfferStatus.OFFERED "
            + "AND s.expiresAt < :now ORDER BY s.createdAt ASC")
    List<Long> findStaleOfferedIds(@Param("now") Instant now, Pageable pageable);

    @Query("""
            SELECT so.id
            FROM SlotOffer so
            WHERE so.status = com.recoverysystem.domain.enums.SlotOfferStatus.OFFERED
              AND so.waitlistEntryId IN :waitlistEntryIds
              AND so.id != :excludedOfferId
            """)
    List<Long> findOfferedIdsByWaitlistEntryIdsExcludingOffer(
            @Param("waitlistEntryIds") List<Long> waitlistEntryIds,
            @Param("excludedOfferId") Long excludedOfferId);

    @Query("""
            SELECT so.id
            FROM SlotOffer so
            WHERE so.status = com.recoverysystem.domain.enums.SlotOfferStatus.OFFERED
              AND so.waitlistEntryId IN (
                  SELECT entry.id
                  FROM WaitlistEntry entry
                  WHERE entry.patientId = :patientId
                    AND entry.status = com.recoverysystem.domain.enums.WaitlistEntryStatus.ACTIVE
                    AND entry.currentAppointmentId != :excludedAppointmentId
              )
              AND EXISTS (
                  SELECT 1
                  FROM RecoveryJob job, Appointment appointment
                  WHERE job.id = so.recoveryJobId
                    AND appointment.id = job.sourceAppointmentId
                    AND appointment.startAt < :intervalEnd
                    AND appointment.endAt > :intervalStart
              )
            """)
    List<Long> findOfferedIdsForOtherActivePatientEntriesOverlapping(
            @Param("patientId") Long patientId,
            @Param("excludedAppointmentId") Long excludedAppointmentId,
            @Param("intervalStart") Instant intervalStart,
            @Param("intervalEnd") Instant intervalEnd);

    @Query("SELECT COUNT(so) > 0 FROM SlotOffer so WHERE so.recoveryJobId = :recoveryJobId "
            + "AND so.waitlistEntryId IN (SELECT we.id FROM WaitlistEntry we "
            + "WHERE we.patientId = :patientId)")
    boolean existsPriorOfferForPatientAndJob(
            @Param("recoveryJobId") Long recoveryJobId,
            @Param("patientId") Long patientId);

    boolean existsByRecoveryJobIdAndStatus(
            Long recoveryJobId, SlotOfferStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SlotOffer s WHERE s.waitlistEntryId = :waitlistEntryId "
            + "AND s.status = :status ORDER BY s.id ASC")
    List<SlotOffer> findByWaitlistEntryIdAndStatusForUpdate(
            @Param("waitlistEntryId") Long waitlistEntryId,
            @Param("status") SlotOfferStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SlotOffer s WHERE s.waitlistEntryId IN :waitlistEntryIds "
            + "AND s.status = :status ORDER BY s.id ASC")
    List<SlotOffer> findByWaitlistEntryIdsAndStatusForUpdate(
            @Param("waitlistEntryIds") List<Long> waitlistEntryIds,
            @Param("status") SlotOfferStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SlotOffer s WHERE s.status = "
            + "com.recoverysystem.domain.enums.SlotOfferStatus.OFFERED "
            + "AND s.recoveryJobId IN :recoveryJobIds ORDER BY s.id ASC")
    List<SlotOffer> findOfferedByRecoveryJobIdsForUpdate(
            @Param("recoveryJobIds") List<Long> recoveryJobIds);
}

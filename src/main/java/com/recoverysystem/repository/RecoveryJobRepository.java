package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.RecoveryJob;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecoveryJobRepository extends JpaRepository<RecoveryJob, Long> {

    @Query("SELECT j.id FROM RecoveryJob j WHERE j.status = "
            + "com.recoverysystem.domain.enums.RecoveryJobStatus.OPEN "
            + "AND NOT EXISTS (SELECT 1 FROM SlotOffer s WHERE s.recoveryJobId = j.id "
            + "AND s.status = com.recoverysystem.domain.enums.SlotOfferStatus.OFFERED) "
            + "ORDER BY j.createdAt ASC")
    List<Long> findOldestOpenJobIdsWithoutOfferedOffer(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM RecoveryJob j WHERE j.id = :id")
    Optional<RecoveryJob> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT j
            FROM RecoveryJob j
            WHERE j.status = com.recoverysystem.domain.enums.RecoveryJobStatus.OPEN
              AND j.sourceAppointmentId IN (
                  SELECT a.id
                  FROM Appointment a
                  WHERE a.providerId = :providerId
                    AND a.startAt < :blockEnd
                    AND a.endAt > :blockStart
              )
            ORDER BY j.id ASC
            """)
    List<RecoveryJob> findOpenOverlappingByProviderForUpdate(
            @Param("providerId") Long providerId,
            @Param("blockStart") Instant blockStart,
            @Param("blockEnd") Instant blockEnd);
}

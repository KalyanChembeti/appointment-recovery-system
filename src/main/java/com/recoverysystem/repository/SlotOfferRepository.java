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

    @Query("SELECT s.id FROM SlotOffer s WHERE s.status = "
            + "com.recoverysystem.domain.enums.SlotOfferStatus.OFFERED "
            + "AND s.expiresAt < :now ORDER BY s.createdAt ASC")
    List<Long> findStaleOfferedIds(@Param("now") Instant now, Pageable pageable);

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

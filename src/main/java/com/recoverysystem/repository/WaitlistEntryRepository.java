package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
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
}

package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.Appointment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    List<Appointment> findByPatientId(Long patientId);

    List<Appointment> findByProviderId(Long providerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Appointment a WHERE a.id = :id")
    Optional<Appointment> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            SELECT a.id
            FROM Appointment a
            WHERE a.providerId = :providerId
              AND a.status = com.recoverysystem.domain.enums.AppointmentStatus.SCHEDULED
              AND a.startAt < :blockEnd
              AND a.endAt > :blockStart
            ORDER BY a.id ASC
            """)
    List<Long> findScheduledOverlappingIds(
            @Param("providerId") Long providerId,
            @Param("blockStart") Instant blockStart,
            @Param("blockEnd") Instant blockEnd);

    @Query("SELECT a.id FROM Appointment a WHERE a.patientId = :patientId "
            + "AND a.status = com.recoverysystem.domain.enums.AppointmentStatus.SCHEDULED "
            + "AND a.startAt < :endAt AND a.endAt > :startAt")
    List<Long> findScheduledOverlappingIdsForPatient(
            @Param("patientId") Long patientId,
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt);
}

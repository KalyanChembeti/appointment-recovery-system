package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.WaitlistEntry;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WaitlistEntry w WHERE w.id = :id")
    Optional<WaitlistEntry> findByIdForUpdate(@Param("id") Long id);
}

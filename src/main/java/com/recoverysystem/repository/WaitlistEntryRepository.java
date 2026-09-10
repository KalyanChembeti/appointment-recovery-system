package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.WaitlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, Long> {
}

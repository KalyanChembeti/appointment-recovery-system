package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.RecoveryJob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryJobRepository extends JpaRepository<RecoveryJob, Long> {
}

package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.ProviderSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderScheduleRepository extends JpaRepository<ProviderSchedule, Long> {
}

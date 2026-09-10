package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.SchedulingPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchedulingPolicyRepository extends JpaRepository<SchedulingPolicy, Long> {
}

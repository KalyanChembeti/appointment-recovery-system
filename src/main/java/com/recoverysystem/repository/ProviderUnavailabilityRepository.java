package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.ProviderUnavailability;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderUnavailabilityRepository extends JpaRepository<ProviderUnavailability, Long> {
}

package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.Provider;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderRepository extends JpaRepository<Provider, Long> {
}

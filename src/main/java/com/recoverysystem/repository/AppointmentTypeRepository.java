package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.AppointmentType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppointmentTypeRepository extends JpaRepository<AppointmentType, Long> {
}

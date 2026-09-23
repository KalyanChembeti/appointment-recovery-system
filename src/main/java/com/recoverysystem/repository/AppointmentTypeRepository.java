package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.AppointmentType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentTypeRepository extends JpaRepository<AppointmentType, Long> {

    @Query("SELECT at FROM AppointmentType at WHERE at.isActive = true "
            + "ORDER BY at.name ASC, at.id ASC")
    List<AppointmentType> findAllActiveOrdered();

    @Query("SELECT at FROM AppointmentType at WHERE at.isActive = true "
            + "AND at.specialtyId = :specialtyId ORDER BY at.name ASC, at.id ASC")
    List<AppointmentType> findActiveBySpecialtyIdOrdered(
            @Param("specialtyId") Long specialtyId);
}

package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.Specialty;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SpecialtyRepository extends JpaRepository<Specialty, Long> {

    @Query("SELECT s FROM Specialty s ORDER BY s.name ASC, s.id ASC")
    List<Specialty> findAllOrdered();
}

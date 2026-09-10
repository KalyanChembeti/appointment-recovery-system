package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.ProviderSchedule;
import java.time.DayOfWeek;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderScheduleRepository extends JpaRepository<ProviderSchedule, Long> {

    @Query("""
            SELECT ps
            FROM ProviderSchedule ps
            WHERE ps.providerId = :providerId
              AND ps.isActive = true
              AND ps.dayOfWeek = :dayOfWeek
            """)
    List<ProviderSchedule> findActiveByProviderIdAndDayOfWeek(
            @Param("providerId") Long providerId,
            @Param("dayOfWeek") DayOfWeek dayOfWeek);
}

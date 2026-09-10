package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.ProviderUnavailability;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderUnavailabilityRepository extends JpaRepository<ProviderUnavailability, Long> {

    @Query("""
            SELECT CASE WHEN COUNT(pu) > 0 THEN true ELSE false END
            FROM ProviderUnavailability pu
            WHERE pu.providerId = :providerId
              AND pu.status IN (
                  com.recoverysystem.domain.enums.ProviderUnavailabilityStatus.ACTIVE,
                  com.recoverysystem.domain.enums.ProviderUnavailabilityStatus.PENDING
              )
              AND pu.startAt < :endAt
              AND :startAt < pu.endAt
            """)
    boolean existsOverlappingActiveOrPendingBlock(
            @Param("providerId") Long providerId,
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt);
}

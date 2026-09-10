package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.ProviderUnavailability;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderUnavailabilityRepository extends JpaRepository<ProviderUnavailability, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pu FROM ProviderUnavailability pu WHERE pu.id = :id")
    Optional<ProviderUnavailability> findByIdForUpdate(@Param("id") Long id);

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

    @Query("SELECT COUNT(pu) > 0 FROM ProviderUnavailability pu WHERE pu.providerId = "
            + ":providerId AND pu.status = com.recoverysystem.domain.enums."
            + "ProviderUnavailabilityStatus.ACTIVE AND pu.startAt < :endAt AND pu.endAt > "
            + ":startAt")
    boolean existsOverlappingActiveBlock(
            @Param("providerId") Long providerId,
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt);
}

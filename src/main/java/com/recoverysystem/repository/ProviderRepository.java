package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.web.dto.ProviderListResponse;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderRepository extends JpaRepository<Provider, Long> {

    Optional<Provider> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Provider p WHERE p.id = :id")
    Optional<Provider> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Provider p WHERE p.id IN :ids ORDER BY p.id ASC")
    List<Provider> findAllByIdInForUpdate(@Param("ids") List<Long> ids);

    @Query("""
            SELECT new com.recoverysystem.web.dto.ProviderListResponse(
                p.id, p.userId, p.specialtyId, u.displayName)
            FROM Provider p
            JOIN User u ON u.id = p.userId
            ORDER BY p.id ASC
            """)
    List<ProviderListResponse> findAllProviderListResponses();

    @Query("""
            SELECT new com.recoverysystem.web.dto.ProviderListResponse(
                p.id, p.userId, p.specialtyId, u.displayName)
            FROM Provider p
            JOIN User u ON u.id = p.userId
            WHERE p.specialtyId = :specialtyId
            ORDER BY p.id ASC
            """)
    List<ProviderListResponse> findProviderListResponsesBySpecialtyId(
            @Param("specialtyId") Long specialtyId);
}

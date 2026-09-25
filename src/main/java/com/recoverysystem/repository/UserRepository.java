package com.recoverysystem.repository;

import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.web.dto.PatientSearchResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    @Query("""
            SELECT new com.recoverysystem.web.dto.PatientSearchResponse(
                user.id,
                user.email,
                user.displayName)
            FROM User user
            WHERE user.role = :role
              AND (
                  LOWER(user.email) LIKE :containsPattern ESCAPE '!'
                  OR LOWER(user.displayName) LIKE :containsPattern ESCAPE '!')
            ORDER BY LOWER(user.email) ASC, user.id ASC
            """)
    List<PatientSearchResponse> searchByRoleAndEmailOrDisplayName(
            @Param("role") UserRole role,
            @Param("containsPattern") String containsPattern,
            Pageable pageable);
}

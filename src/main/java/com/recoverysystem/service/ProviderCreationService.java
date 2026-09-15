package com.recoverysystem.service;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.exception.InvalidProviderUserRoleException;
import com.recoverysystem.exception.SpecialtyNotFoundException;
import com.recoverysystem.exception.UserAlreadyLinkedToProviderException;
import com.recoverysystem.exception.UserNotFoundException;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderCreationService {

    private static final String PROVIDER_USER_ID_UNIQUE_CONSTRAINT = "provider_user_id_key";

    private final UserRepository userRepository;
    private final ProviderRepository providerRepository;
    private final SpecialtyRepository specialtyRepository;
    private final AuditLogRepository auditLogRepository;

    public ProviderCreationService(
            UserRepository userRepository,
            ProviderRepository providerRepository,
            SpecialtyRepository specialtyRepository,
            AuditLogRepository auditLogRepository) {
        this.userRepository = userRepository;
        this.providerRepository = providerRepository;
        this.specialtyRepository = specialtyRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public Provider createProvider(
            Long userId,
            Long specialtyId,
            String licenseNumber,
            String qualifications,
            Long actorUserId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getRole() != UserRole.PROVIDER) {
            throw new InvalidProviderUserRoleException(userId, user.getRole());
        }
        if (providerRepository.findByUserId(userId).isPresent()) {
            throw new UserAlreadyLinkedToProviderException(userId);
        }
        specialtyRepository.findById(specialtyId)
                .orElseThrow(() -> new SpecialtyNotFoundException(specialtyId));

        Provider provider = new Provider();
        provider.setUserId(userId);
        provider.setSpecialtyId(specialtyId);
        provider.setLicenseNumber(licenseNumber);
        provider.setQualifications(qualifications);

        Provider persistedProvider;
        try {
            persistedProvider = providerRepository.save(provider);
            providerRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, PROVIDER_USER_ID_UNIQUE_CONSTRAINT)) {
                throw new UserAlreadyLinkedToProviderException(userId, exception);
            }
            throw exception;
        }

        AuditLog auditLog = new AuditLog();
        auditLog.setEntityType("Provider");
        auditLog.setEntityId(persistedProvider.getId());
        auditLog.setAction("CREATE");
        auditLog.setActorType(ActorType.USER);
        auditLog.setActorUserId(actorUserId);
        auditLogRepository.save(auditLog);

        return persistedProvider;
    }

    private boolean hasConstraint(Throwable throwable, String constraintName) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && constraintName.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            if (current.getMessage() != null
                    && current.getMessage().contains(constraintName)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }
}

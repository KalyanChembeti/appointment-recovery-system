package com.recoverysystem.web.security;

import com.recoverysystem.exception.AppointmentOwnershipException;
import com.recoverysystem.exception.MissingPatientIdException;
import com.recoverysystem.exception.PatientIdentityMismatchException;
import com.recoverysystem.exception.ProviderActionNotPermittedException;
import com.recoverysystem.security.AuthenticatedUser;
import org.springframework.stereotype.Component;

@Component
public class EffectivePatientIdResolver {

    public Long resolve(Long requestedPatientId, AuthenticatedUser caller) {
        return switch (caller.getRole()) {
            case PATIENT -> resolvePatientIdentity(requestedPatientId, caller);
            case RECEPTIONIST, ADMIN -> resolveStaffPatientIdentity(requestedPatientId, caller);
            case PROVIDER -> throw new ProviderActionNotPermittedException(caller.getRole());
        };
    }

    public void verifyOwnership(Long effectivePatientId, Long actualPatientId) {
        if (!effectivePatientId.equals(actualPatientId)) {
            throw new AppointmentOwnershipException(effectivePatientId, actualPatientId);
        }
    }

    private Long resolvePatientIdentity(
            Long requestedPatientId, AuthenticatedUser caller) {
        if (requestedPatientId == null) {
            return caller.getUserId();
        }
        if (!requestedPatientId.equals(caller.getUserId())) {
            throw new PatientIdentityMismatchException(
                    requestedPatientId, caller.getUserId());
        }
        return requestedPatientId;
    }

    private Long resolveStaffPatientIdentity(
            Long requestedPatientId, AuthenticatedUser caller) {
        if (requestedPatientId == null) {
            throw new MissingPatientIdException(caller.getRole());
        }
        return requestedPatientId;
    }
}
